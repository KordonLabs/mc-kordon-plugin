package com.kordonlabs.plugin.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * The plugin's two {@link HttpClient}s: a dedicated <b>login</b> client, so a slow sync can never hold a connection
 * a join is waiting for, and a <b>background</b> client for everything else. Plain-HTTP panels are spoken to with
 * HTTP/1.1 to avoid the h2c upgrade. Redirects are <b>never</b> followed so the token cannot travel to another host.
 * Answers are read as UTF-8 and capped at {@link #MAX_RESPONSE_BYTES}; a larger body fails the call like a network
 * error. The executors are owned by the caller. With an {@link OutboundProxy} both clients go through it; an invalid
 * proxy fails every request, and a 407 from the proxy fails the request after calling {@code onProxyRejected}.
 */
public final class HttpTransport {

    /** Which client carries a request. */
    public enum Channel { LOGIN, BACKGROUND }

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** How long {@link #close()} waits for both clients together. */
    public static final Duration CLOSE_AWAIT = Duration.ofMillis(500);
    /** The panel's answers are a few KB; a 1 MiB body is not a Kordon panel. */
    public static final int MAX_RESPONSE_BYTES = 1 << 20;

    private final String userAgent;
    private final HttpClient login;
    private final HttpClient background;
    private final OutboundProxy proxy;
    private final Runnable onProxyRejected;
    private volatile boolean closed;

    public HttpTransport(String userAgent, Executor loginExecutor, Executor backgroundExecutor) {
        this(userAgent, loginExecutor, backgroundExecutor, OutboundProxy.NONE, () -> { });
    }

    public HttpTransport(String userAgent, Executor loginExecutor, Executor backgroundExecutor, OutboundProxy proxy,
                         Runnable onProxyRejected) {
        this.userAgent = userAgent;
        this.proxy = proxy;
        this.onProxyRejected = onProxyRejected;
        this.login = client(loginExecutor, proxy);
        this.background = client(backgroundExecutor, proxy);
    }

    private static HttpClient client(Executor executor, OutboundProxy proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        proxy.applyTo(builder);
        return builder.build();
    }

    /**
     * POSTs {@code json} to {@code uri} with the bearer token. The future completes with the response, or
     * exceptionally on a network failure, a timeout or after close. Cancelling the future aborts the exchange.
     */
    public CompletableFuture<HttpResponse<String>> post(Channel channel, URI uri, String bearerToken, String json,
                                                        Duration timeout) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Kordon HTTP clients are closed"));
        if (proxy.invalid()) return CompletableFuture.failedFuture(new IOException("invalid -Dkordon.proxy.* flags"));
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .version(tls ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        String proxyLogin = proxy.authorization();
        if (proxyLogin != null) builder.header("Proxy-Authorization", proxyLogin); // sent to the proxy only
        HttpRequest request = builder.build();
        HttpClient client = channel == Channel.LOGIN ? login : background;
        CompletableFuture<HttpResponse<String>> sent = client.sendAsync(request,
                info -> new LimitedString(MAX_RESPONSE_BYTES));
        if (!proxy.configured()) return sent;
        CompletableFuture<HttpResponse<String>> out = sent.thenCompose(response -> {
            if (response.statusCode() != 407) return CompletableFuture.completedFuture(response);
            proxyRejected();
            return CompletableFuture.failedFuture(new IOException("the HTTP proxy refused the request (407)"));
        });
        out.whenComplete((response, error) -> {
            if (out.isCancelled()) sent.cancel(true);
        });
        return out;
    }

    private void proxyRejected() {
        try {
            onProxyRejected.run();
        } catch (RuntimeException ignored) {
            // a console line must never break a request
        }
    }

    /** Shuts both clients down and waits at most {@link #CLOSE_AWAIT} for both. In-flight requests fail. Idempotent. */
    public void close() {
        closed = true;
        login.shutdownNow();
        background.shutdownNow();
        long deadline = System.nanoTime() + CLOSE_AWAIT.toNanos();
        try {
            for (HttpClient c : List.of(login, background)) {
                long left = Math.max(0, deadline - System.nanoTime());
                c.awaitTermination(Duration.ofNanos(left));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isTerminated() {
        return login.isTerminated() && background.isTerminated();
    }

    List<HttpClient> clients() {
        return List.of(login, background);
    }

    /** A UTF-8 string body that fails (and aborts the exchange) past {@code max} bytes. */
    private static final class LimitedString implements HttpResponse.BodySubscriber<String> {

        private final int max;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedString(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                if (bytes.size() + b.remaining() > max) {
                    result.completeExceptionally(new IOException("response larger than " + max + " bytes"));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
