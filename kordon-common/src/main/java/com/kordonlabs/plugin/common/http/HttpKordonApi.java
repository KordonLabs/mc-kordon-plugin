package com.kordonlabs.plugin.common.http;

import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.ApiResult.Outcome;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.ErrorBody;
import com.kordonlabs.plugin.common.api.dto.HeartbeatRequest;
import com.kordonlabs.plugin.common.api.dto.HeartbeatResponse;
import com.kordonlabs.plugin.common.api.dto.ListsRequest;
import com.kordonlabs.plugin.common.api.dto.ListsResponse;
import com.kordonlabs.plugin.common.api.dto.LoginRequest;
import com.kordonlabs.plugin.common.api.dto.LoginResponse;
import com.kordonlabs.plugin.common.api.dto.LookupRequest;
import com.kordonlabs.plugin.common.api.dto.LookupResponse;
import com.kordonlabs.plugin.common.api.dto.SyncRequest;
import com.kordonlabs.plugin.common.api.dto.SyncResponse;
import com.kordonlabs.plugin.common.http.HttpTransport.Channel;
import com.kordonlabs.plugin.common.install.Credentials;
import com.kordonlabs.plugin.common.install.Tokens;
import com.kordonlabs.plugin.common.json.Json;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link KordonApi} over {@link HttpTransport}: {@code POST <apiUrl>/api/v1/mc/<name>} with the {@link Credentials}
 * in use at the time of the call, or with explicit candidate credentials for the validation heartbeat of
 * {@code /kordon link}. Without credentials the call is not made and the result is {@code NOT_CONFIGURED}.
 *
 * <ul>
 *   <li><b>Timeouts</b>: {@code HttpRequest.timeout} covers the response headers; background calls give the body
 *       {@link #BODY_GRACE} more, while login is capped at 3 s in total. A call that runs out of time is aborted.</li>
 *   <li><b>Retries</b>: at most one, and only after a stale-connection failure. Never after a timeout (a timed-out
 *       mutation has probably been applied already), never after an HTTP answer and never for login.</li>
 *   <li><b>Classification</b>: a non-JSON 403 is UNSUPPORTED rather than a server error, so a firewall page cannot
 *       switch the plugin to letting everyone in. Redirects are reported with their target origin and never
 *       followed.</li>
 * </ul>
 * Futures never complete exceptionally. Debug lines show the path, status, duration and both bodies, truncated and
 * with every key and IP address masked; headers are never logged.
 *
 * <p>Every attempt that got an HTTP answer (of any status, and only if the call was not aborted) records its response
 * time in the {@link PingMeter}.
 */
public final class HttpKordonApi implements KordonApi {

    public static final String BASE_PATH = "/api/v1/mc/";
    public static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration SYNC_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    /** Background calls only: extra time to read the body after the headers arrived. */
    static final Duration BODY_GRACE = Duration.ofSeconds(2);
    static final int LOG_BODY_MAX = 512;
    /** Player addresses are masked in debug bodies: JSON {@code "ip"} values... */
    private static final Pattern IP_FIELD = Pattern.compile("(\"ip\"\\s*:\\s*)\"[^\"]*\"");
    /** ...and addresses in free text (lookup lines, lists values, a query). */
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9.])[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?![0-9.])");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f:])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-f:])");
    static final String IP_MASK = "<ip>";

    /** Per-call timeouts; tests shorten them. {@code bodyGrace} applies to background calls only. */
    record Timeouts(Duration login, Duration heartbeat, Duration sync, Duration command, Duration bodyGrace) {
        static final Timeouts DEFAULT = new Timeouts(LOGIN_TIMEOUT, HEARTBEAT_TIMEOUT, SYNC_TIMEOUT, COMMAND_TIMEOUT,
                BODY_GRACE);
    }

    private final HttpTransport transport;
    private final Supplier<Credentials> credentials;
    private final ThrottledLog log;
    private final Timeouts timeouts;
    /** Null when response times are not recorded. */
    private final PingMeter ping;

    /** Without a {@link PingMeter}. */
    public HttpKordonApi(HttpTransport transport, Supplier<Credentials> credentials, ThrottledLog log) {
        this(transport, credentials, log, null, Timeouts.DEFAULT);
    }

    /** @param ping where answered requests record their response time; may be null */
    public HttpKordonApi(HttpTransport transport, Supplier<Credentials> credentials, ThrottledLog log, PingMeter ping) {
        this(transport, credentials, log, ping, Timeouts.DEFAULT);
    }

    HttpKordonApi(HttpTransport transport, Supplier<Credentials> credentials, ThrottledLog log, Timeouts timeouts) {
        this(transport, credentials, log, null, timeouts);
    }

    HttpKordonApi(HttpTransport transport, Supplier<Credentials> credentials, ThrottledLog log, PingMeter ping,
                  Timeouts timeouts) {
        this.transport = transport;
        this.credentials = credentials;
        this.log = log;
        this.ping = ping;
        this.timeouts = timeouts;
    }

    @Override
    public CompletableFuture<ApiResult<LoginResponse>> login(LoginRequest request) {
        return call("login", Channel.LOGIN, request, LoginResponse.class, LoginResponse::wellFormed,
                timeouts.login(), timeouts.login(), false, null);
    }

    @Override
    public CompletableFuture<ApiResult<HeartbeatResponse>> heartbeat(HeartbeatRequest request) {
        return heartbeat(request, null);
    }

    @Override
    public CompletableFuture<ApiResult<HeartbeatResponse>> heartbeat(HeartbeatRequest request, Credentials with) {
        return call("heartbeat", Channel.BACKGROUND, request, HeartbeatResponse.class, HeartbeatResponse::wellFormed,
                timeouts.heartbeat(), timeouts.heartbeat().plus(timeouts.bodyGrace()), true, with);
    }

    @Override
    public CompletableFuture<ApiResult<SyncResponse>> sync(SyncRequest request) {
        return call("sync", Channel.BACKGROUND, request, SyncResponse.class, SyncResponse::wellFormed,
                timeouts.sync(), timeouts.sync().plus(timeouts.bodyGrace()), true, null);
    }

    @Override
    public CompletableFuture<ApiResult<ListsResponse>> lists(ListsRequest request) {
        return call("lists", Channel.BACKGROUND, request, ListsResponse.class, ListsResponse::wellFormed,
                timeouts.command(), timeouts.command().plus(timeouts.bodyGrace()), true, null);
    }

    @Override
    public CompletableFuture<ApiResult<LookupResponse>> lookup(LookupRequest request) {
        return call("lookup", Channel.BACKGROUND, request, LookupResponse.class, LookupResponse::wellFormed,
                timeouts.command(), timeouts.command().plus(timeouts.bodyGrace()), true, null);
    }

    // ── one call ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Everything one call needs, fixed at its start, including the credentials of that moment. */
    private record Call<T>(String path, Channel channel, URI uri, String token, String json, Class<T> type,
                           Predicate<T> wellFormed, Duration timeout, Duration cap) {

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Call[" + uri + ", " + channel + "]";
        }
    }

    /**
     * One HTTP attempt: its classified result, whether it failed on a stale connection (retryable), and its response
     * time in ms when it got an HTTP answer ({@code -1} when it did not).
     */
    private record Attempt<T>(ApiResult<T> result, boolean stale, long answeredMillis) {
    }

    private <T> CompletableFuture<ApiResult<T>> call(String name, Channel channel, Object request, Class<T> type,
                                                    Predicate<T> wellFormed, Duration timeout, Duration cap,
                                                    boolean retryStale, Credentials override) {
        try {
            Credentials c = override != null ? override : credentials.get();
            if (c == null || !c.configured()) {
                return CompletableFuture.completedFuture(ApiResult.noAnswer(Outcome.NOT_CONFIGURED));
            }
            String json = Json.toJson(request);
            if (json == null) {
                return CompletableFuture.completedFuture(ApiResult.failure(Outcome.BAD_REQUEST, 0, null, null));
            }
            String path = BASE_PATH + name;
            Call<T> call = new Call<>(path, channel, URI.create(c.apiUrl() + path), c.token(), json, type,
                    wellFormed, timeout, cap);
            CompletableFuture<ApiResult<T>> result = attempt(call).thenCompose(first -> {
                if (!retryStale || !first.stale()) return CompletableFuture.completedFuture(first.result());
                log.debug(() -> "POST " + path + ": stale connection, retrying once");
                return attempt(call).thenApply(second -> second.result().withRetried());
            });
            return result.exceptionally(t -> ApiResult.noAnswer(Outcome.NETWORK));
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(ApiResult.noAnswer(Outcome.NETWORK));
        }
    }

    private <T> CompletableFuture<Attempt<T>> attempt(Call<T> call) {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> sent;
        try {
            sent = transport.post(call.channel(), call.uri(), call.token(), call.json(), call.timeout());
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(failed(call, t, started));
        }
        CompletableFuture<Attempt<T>> out = new CompletableFuture<>();
        sent.whenComplete((response, error) -> {
            Attempt<T> attempt;
            try {
                attempt = error == null ? answered(call, response, started) : failed(call, error, started);
            } catch (Throwable t) {
                attempt = failed(call, t, started);
            }
            // An answer after the whole-call cap belongs to an aborted call and is not a ping sample.
            if (out.complete(attempt) && attempt.answeredMillis() >= 0) recordPing(attempt.answeredMillis());
        });
        out.orTimeout(call.cap().toMillis(), TimeUnit.MILLISECONDS);
        return out.handle((attempt, error) -> {
            if (attempt != null) return attempt;
            sent.cancel(true); // the whole-call cap was reached: abort the exchange
            return failed(call, error, started);
        });
    }

    // ── classification ───────────────────────────────────────────────────────────────────────────────────────────

    private <T> Attempt<T> answered(Call<T> call, HttpResponse<String> response, long started) {
        long ms = millis(started);
        int status = response.statusCode();
        String body = response.body();
        ApiResult<T> result = classify(call, response, status, body);
        log.debug(() -> "POST " + call.path() + " → " + status + " in " + ms + " ms"
                + (result.ok() ? "" : " (" + result.outcome() + ")")
                + "; request " + preview(call.json()) + "; response " + preview(body));
        return new Attempt<>(result, false, ms);
    }

    private void recordPing(long ms) {
        PingMeter meter = ping;
        if (meter == null) return;
        try {
            meter.record(ms);
        } catch (Throwable t) {
            log.debug(() -> "ping not recorded: " + t);
        }
    }

    private <T> ApiResult<T> classify(Call<T> call, HttpResponse<String> response, int status, String body) {
        if (status >= 200 && status < 300) {
            T parsed = Json.fromJson(body, call.type());
            return parsed != null && call.wellFormed().test(parsed)
                    ? ApiResult.ok(parsed, status)
                    : ApiResult.failure(Outcome.UNSUPPORTED, status, null, null);
        }
        if (status >= 300 && status < 400) return ApiResult.redirect(status, locationOrigin(response));
        ErrorBody.Detail error = errorOf(body);
        String code = error == null ? null : error.code();
        String message = error == null ? null : error.message();
        if (status == 429) return ApiResult.rateLimited(status, retryAfterSec(response));
        Outcome outcome = switch (status) {
            case 400 -> Outcome.BAD_REQUEST;
            case 401 -> Outcome.UNAUTHORIZED;
            case 403 -> "account_disabled".equals(code) ? Outcome.ACCOUNT_DISABLED
                    : "commands_disabled".equals(code) ? Outcome.COMMANDS_DISABLED
                    : error == null ? Outcome.UNSUPPORTED : Outcome.SERVER_ERROR;
            case 404, 405 -> Outcome.UNSUPPORTED;
            case 413 -> Outcome.TOO_LARGE;
            case 422 -> error == null ? Outcome.UNSUPPORTED : Outcome.INVALID_VALUE;
            default -> status >= 500 || error != null ? Outcome.SERVER_ERROR : Outcome.UNSUPPORTED;
        };
        return ApiResult.failure(outcome, status, code, message);
    }

    private <T> Attempt<T> failed(Call<T> call, Throwable error, long started) {
        Throwable cause = unwrap(error);
        boolean timeout = cause instanceof HttpTimeoutException || cause instanceof TimeoutException;
        boolean stale = !timeout && isStale(cause);
        log.debug(() -> "POST " + call.path() + " → " + (timeout ? "timeout" : cause.getClass().getSimpleName())
                + " in " + millis(started) + " ms" + (stale ? " (stale connection)" : ""));
        return new Attempt<>(ApiResult.noAnswer(timeout ? Outcome.TIMEOUT : Outcome.NETWORK), stale, -1);
    }

    /**
     * A pooled connection the server already closed, or nobody listening. Such a request never reached the panel, so
     * it is safe to repeat once.
     */
    static boolean isStale(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof HttpTimeoutException) return false;
            if (t instanceof ConnectException || t instanceof EOFException) return true;
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("header parser received no bytes") || m.contains("connection reset")) return true;
            }
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static ErrorBody.Detail errorOf(String body) {
        if (body == null || body.isBlank() || body.charAt(0) != '{') return null;
        ErrorBody parsed = Json.fromJson(body, ErrorBody.class);
        return parsed == null || parsed.error() == null || parsed.error().code() == null ? null : parsed.error();
    }

    /** {@code retry-after} in seconds (0..3600); 0 when absent or not a number. */
    static int retryAfterSec(HttpResponse<?> response) {
        String value = response.headers().firstValue("retry-after").orElse(null);
        if (value == null) return 0;
        try {
            return (int) Math.max(0, Math.min(3600, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code scheme://host[:port]} of the {@code Location}, resolved against the request; null when unusable. */
    static String locationOrigin(HttpResponse<?> response) {
        String location = response.headers().firstValue("location").orElse(null);
        if (location == null || location.isBlank()) return null;
        try {
            URI target = response.request().uri().resolve(location.trim());
            if (target.getScheme() == null || target.getHost() == null) return null;
            return target.getScheme().toLowerCase(Locale.ROOT) + "://" + target.getHost()
                    + (target.getPort() == -1 ? "" : ":" + target.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A debug-safe copy of a body: at most {@link #LOG_BODY_MAX} chars, every key cut to its prefix
     * ({@link Tokens#maskAll}) and every IP address replaced with {@value #IP_MASK}.
     */
    static String preview(String body) {
        if (body == null) return "null";
        String masked = maskIps(Tokens.maskAll(body));
        return masked.length() > LOG_BODY_MAX ? masked.substring(0, LOG_BODY_MAX) + "…" : masked;
    }

    /** Replaces {@code "ip"} values and IPv4/IPv6 addresses with {@value #IP_MASK}; times like 12:00:00 stay. */
    static String maskIps(String text) {
        String s = IP_FIELD.matcher(text).replaceAll("$1\"" + IP_MASK + "\"");
        s = IPV4.matcher(s).replaceAll(IP_MASK);
        Matcher v6 = IPV6.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (v6.find()) {
            String candidate = v6.group();
            boolean address = candidate.contains("::") || candidate.chars().filter(c -> c == ':').count() >= 4;
            v6.appendReplacement(out, Matcher.quoteReplacement(address ? IP_MASK : candidate));
        }
        v6.appendTail(out);
        return out.toString();
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
