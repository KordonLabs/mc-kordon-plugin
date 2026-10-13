package com.kordonlabs.plugin.common.install;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation of a Kordon API address, whether embedded in the jar, given with {@code -Dkordon.panelUrl} or built in:
 * {@code ^https?://[^\s/?#@]+(:\d{1,5})?(/[^\s?#]*)?$} — query, fragment and credentials are rejected, the trailing
 * {@code /} is stripped. The value must also be a URI {@code java.net.http} can call (a host and a valid port).
 */
public final class PanelUrls {

    private static final Pattern URL = Pattern.compile("https?://[^\\s/?#@]+(:\\d{1,5})?(/[^\\s?#]*)?");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private PanelUrls() {
    }

    public static boolean valid(String url) {
        if (url == null || !URL.matcher(url).matches()) return false;
        try {
            URI uri = new URI(url);
            int port = uri.getPort();
            return uri.getHost() != null && (port == -1 || (port > 0 && port <= 65_535));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The URL with the trailing {@code /} stripped; null when not {@link #valid}. */
    public static String normalize(String url) {
        if (!valid(url)) return null;
        String s = url;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The key would travel unencrypted. */
    public static boolean plainHttp(String url) {
        return url != null && url.startsWith("http://");
    }

    /**
     * A loopback or private host (RFC 1918, 127/8, {@code ::1}, {@code fc00::/7}, {@code localhost}), where plain HTTP
     * is acceptable. Never resolves names via DNS.
     */
    public static boolean isPrivateHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        Matcher v4 = IPV4.matcher(host);
        if (v4.matches()) {
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(v4.group(i + 1));
                if (o[i] > 255) return false;
            }
            return privateV4(o[0], o[1]);
        }
        if (host.indexOf(':') < 0) return false; // a name: public unless it is localhost
        try {
            InetAddress address = InetAddress.getByName(host); // an IPv6 literal: parsed, never looked up
            if (address instanceof Inet4Address) { // ::ffff:a.b.c.d
                byte[] b = address.getAddress();
                return privateV4(b[0] & 0xFF, b[1] & 0xFF);
            }
            if (address instanceof Inet6Address) {
                return address.isLoopbackAddress() || (address.getAddress()[0] & 0xFE) == 0xFC;
            }
            return false;
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    private static boolean privateV4(int a, int b) {
        return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }
}
