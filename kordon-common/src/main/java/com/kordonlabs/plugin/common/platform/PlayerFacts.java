package com.kordonlabs.plugin.common.platform;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;

/**
 * Connection facts every platform reports the same way: the canonical IP of a socket address, the join host, a client
 * locale as BCP 47 and a backend name, each limited to what the panel accepts.
 */
public final class PlayerFacts {

    /** The panel rejects a locale above 32 chars and a backend name above 128. */
    public static final int MAX_LOCALE = 32;
    public static final int MAX_BACKEND = 128;
    /** The longest DNS name. */
    public static final int MAX_HOST = 253;

    private PlayerFacts() {
    }

    /**
     * The canonical host address of an {@link InetSocketAddress} ({@code %scope} of an IPv6 address removed); null for
     * any other kind of address (a Unix domain socket listener) and for null.
     */
    public static String ipOf(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) return null;
        InetAddress resolved = inet.getAddress();
        String host = resolved != null ? resolved.getHostAddress() : inet.getHostString();
        if (host == null) return null;
        int scope = host.indexOf('%');
        if (scope >= 0) host = host.substring(0, scope);
        return host.isEmpty() ? null : host;
    }

    /**
     * The address the player typed to join, from the handshake's virtual host: lower case, without a trailing dot and
     * without what mods and relays append after a NUL or {@code ///} (Forge's {@code \0FML\0}, TCPShield's data); null
     * when unknown, empty or above {@value #MAX_HOST} chars.
     */
    public static String joinHost(InetSocketAddress virtualHost) {
        if (virtualHost == null) return null;
        String host = virtualHost.getHostString();
        if (host == null) return null;
        int cut = host.indexOf('\0');
        if (cut >= 0) host = host.substring(0, cut);
        cut = host.indexOf("///");
        if (cut >= 0) host = host.substring(0, cut);
        host = host.strip();
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > MAX_HOST) return null;
        return host.toLowerCase(Locale.ROOT);
    }

    /** {@code locale} as BCP 47; null for null, for {@code und} (unparsable) and above {@value #MAX_LOCALE} chars. */
    public static String localeTag(Locale locale) {
        if (locale == null) return null;
        String tag = locale.toLanguageTag();
        if (tag.isEmpty() || "und".equals(tag) || tag.length() > MAX_LOCALE) return null;
        return tag;
    }

    /** A backend server name cut to {@value #MAX_BACKEND} chars; null for null or empty. */
    public static String backend(String name) {
        if (name == null || name.isEmpty()) return null;
        return name.length() > MAX_BACKEND ? name.substring(0, MAX_BACKEND) : name;
    }
}
