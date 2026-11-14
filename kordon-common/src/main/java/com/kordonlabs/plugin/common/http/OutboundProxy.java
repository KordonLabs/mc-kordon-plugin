package com.kordonlabs.plugin.common.http;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * An optional HTTP proxy for Kordon's requests, from {@code -Dkordon.proxy.host}, {@code -Dkordon.proxy.port}
 * (default 3128), {@code -Dkordon.proxy.username} and {@code -Dkordon.proxy.password}. Only Kordon's clients use it.
 * Any proxy flag without a usable host or port makes every request fail instead of going direct. The login is sent as
 * a {@code Proxy-Authorization} header (an {@link java.net.Authenticator} would drop the bearer token on Java 21) and
 * is never logged.
 */
public final class OutboundProxy {

    public static final String HOST_PROPERTY = "kordon.proxy.host";
    public static final String PORT_PROPERTY = "kordon.proxy.port";
    public static final String USERNAME_PROPERTY = "kordon.proxy.username";
    public static final String PASSWORD_PROPERTY = "kordon.proxy.password";
    /** The JDK list of schemes not sent through a CONNECT tunnel; its default ({@code net.properties}) is Basic. */
    public static final String TUNNELING_DISABLED_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes";
    /** The same list for a plain-http request through the proxy; its JDK default is empty. */
    public static final String PROXYING_DISABLED_PROPERTY = "jdk.http.auth.proxying.disabledSchemes";
    public static final int DEFAULT_PORT = 3128;

    /** A host name or an IPv4 address (ASCII; an IDN goes in its xn-- form). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    /** An IPv6 literal, optionally with a zone ({@code fe80::1%eth0}). */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[A-Za-z0-9._-]+)?");

    public static final OutboundProxy NONE = new OutboundProxy(null, 0, null, null, false, false, false);

    /** Null for {@link #NONE}; "" when the flags name no host. */
    private final String host;
    private final int port;
    private final String username;
    /** {@code Basic base64(user:password)}, or null without a login. */
    private final String authorization;
    private final boolean invalid;
    private final boolean badHost;
    private final boolean partialCredentials;

    private OutboundProxy(String host, int port, String username, String authorization, boolean invalid,
                          boolean badHost, boolean partialCredentials) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.authorization = authorization;
        this.invalid = invalid;
        this.badHost = badHost;
        this.partialCredentials = partialCredentials;
    }

    /** Reads the four flags through {@code property} (null = not set). */
    public static OutboundProxy fromProperties(UnaryOperator<String> property) {
        String hostRaw = property.apply(HOST_PROPERTY);
        String portRaw = property.apply(PORT_PROPERTY);
        String userRaw = property.apply(USERNAME_PROPERTY);
        String passRaw = property.apply(PASSWORD_PROPERTY);
        if (hostRaw == null && portRaw == null && userRaw == null && passRaw == null) return NONE;

        String host = hostRaw == null ? "" : hostRaw.trim();
        boolean bracketed = host.length() > 2 && host.startsWith("[") && host.endsWith("]");
        if (bracketed) host = host.substring(1, host.length() - 1); // [::1]
        boolean badHost = !host.isEmpty() && !(bracketed ? IPV6.matcher(host).matches()
                : NAME.matcher(host).matches() || IPV6.matcher(host).matches());
        if (badHost) host = "?"; // never kept: "user:pass@host" would print a password
        boolean invalid = host.isEmpty() || badHost;
        String portText = portRaw == null ? "" : portRaw.trim();
        int port = DEFAULT_PORT;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 1 || port > 65535) invalid = true;
        }
        String user = nonEmpty(userRaw);
        String pass = nonEmpty(passRaw);
        boolean partial = (user == null) != (pass == null);
        boolean withLogin = user != null && pass != null && !invalid;
        String authorization = withLogin ? "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)) : null;
        return new OutboundProxy(host, invalid ? 0 : port, withLogin ? user : null, authorization, invalid, badHost,
                partial);
    }

    /** From {@link System#getProperty}; a property that cannot be read counts as not set. */
    public static OutboundProxy fromSystemProperties() {
        return fromProperties(OutboundProxy::systemProperty);
    }

    /** At least one {@code -Dkordon.proxy.*} flag is set (valid or not). */
    public boolean configured() {
        return host != null;
    }

    /** A proxy flag is set but host or port is not usable: requests fail instead of going direct. */
    public boolean invalid() {
        return invalid;
    }

    /** The flags name no host at all (only port or login, or a blank host). */
    public boolean hostMissing() {
        return host != null && host.isEmpty();
    }

    /** The host flag is not a plain host name or IP address (a scheme, a login, a port, a space…). */
    public boolean badHost() {
        return badHost;
    }

    public boolean hasCredentials() {
        return authorization != null;
    }

    /** Only one of username and password was given: both are ignored. */
    public boolean partialCredentials() {
        return partialCredentials;
    }

    public String username() {
        return username;
    }

    /** The {@code Proxy-Authorization} header value, or null without a login. */
    String authorization() {
        return authorization;
    }

    /** {@code host:port} ({@code [v6]:port}); the host alone when the port is invalid; "?" for a bad host. */
    public String address() {
        if (host == null) return "";
        if (badHost) return "?";
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return invalid ? h : h + ":" + port;
    }

    /**
     * The JDK setting that keeps the login from the proxy, or null when it is sent. For an {@code https} panel the JDK
     * drops Basic from the CONNECT unless {@value #TUNNELING_DISABLED_PROPERTY} allows it (default: Basic is disabled);
     * for {@code http}, {@value #PROXYING_DISABLED_PROPERTY} (default: empty). {@code netProperty} reads the setting
     * the way the JDK does ({@link #netProperty(String)}: the system property, then {@code conf/net.properties}).
     */
    public String loginBlockedBy(String apiUrl, UnaryOperator<String> netProperty) {
        if (!hasCredentials()) return null;
        boolean tls = apiUrl != null && apiUrl.regionMatches(true, 0, "https:", 0, 6);
        String key = tls ? TUNNELING_DISABLED_PROPERTY : PROXYING_DISABLED_PROPERTY;
        String value = netProperty.apply(key);
        if (value == null) value = tls ? "Basic" : "";
        for (String scheme : value.split(",")) {
            if (scheme.trim().equalsIgnoreCase("basic")) return key;
        }
        return null;
    }

    /**
     * A JDK networking setting as the HTTP client sees it: the system property, else {@code conf/net.properties} of the
     * running Java (null when neither has it or they cannot be read).
     */
    public static String netProperty(String key) {
        String value = systemProperty(key);
        if (value != null) return value;
        try {
            String home = System.getProperty("java.home");
            if (home == null) return null;
            Path file = Path.of(home, "conf", "net.properties");
            if (!Files.isRegularFile(file)) return null;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            return props.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Points {@code builder} at the proxy; nothing for {@link #NONE} or an invalid proxy. */
    void applyTo(HttpClient.Builder builder) {
        if (host == null || invalid) return;
        // Unresolved: the name is looked up on every new connection, so a proxy that changes its address keeps working.
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(host, port)));
    }

    @Override
    public String toString() {
        return host == null ? "no proxy" : "HTTP proxy " + address() + (invalid ? " (invalid)" : "");
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String systemProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
