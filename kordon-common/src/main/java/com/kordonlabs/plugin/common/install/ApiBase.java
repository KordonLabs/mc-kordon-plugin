package com.kordonlabs.plugin.common.install;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the Kordon API address the plugin sends its requests to, and the panel pages people open. The address is
 * never stored on disk; it is resolved at every start, in this order:
 * <ol>
 *   <li>{@code -Dkordon.panelUrl=<url>} ({@link com.kordonlabs.plugin.common.KordonInfo#PANEL_URL_PROPERTY}), for
 *       development and tests; an invalid value is ignored;</li>
 *   <li>the {@code panelUrl} built into a personal Kordon.jar, with the legacy Kordon hosts mapped to
 *       {@link #KORDON_API};</li>
 *   <li>the build's default address.</li>
 * </ol>
 * Pure: no I/O, never throws.
 */
public final class ApiBase {

    /** The hosted Kordon API, embedded into every personal download from the hosted panel. */
    public static final String KORDON_API = "https://api.kordonlabs.com";
    /** The hosted Kordon panel that people open in a browser. */
    public static final String KORDON_PANEL = "https://panel.kordonlabs.com";
    /** Hosts that served the plugin API before api.kordonlabs.com; their {@code /api/v1} is deprecated. */
    static final Set<String> LEGACY_HOSTS = Set.of("kordonlabs.com", "www.kordonlabs.com", "panel.kordonlabs.com");

    /** Which rule gave the address. */
    public enum Origin { PROPERTY, JAR, DEFAULT, NONE }

    /**
     * @param url              the API base (normalised, no trailing {@code /}); null only when even the build default
     *                         is unusable
     * @param propertyInvalid  {@code -Dkordon.panelUrl} was set but is not a valid address and was ignored
     * @param legacyNormalised the embedded address was a legacy Kordon host, mapped to {@link #KORDON_API}
     */
    public record Resolution(String url, Origin origin, boolean propertyInvalid, boolean legacyNormalised) {
    }

    private ApiBase() {
    }

    public static Resolution resolve(String property, InstallInfo embedded, String builtIn) {
        boolean invalid = false;
        if (property != null && !property.isBlank()) {
            String url = PanelUrls.normalize(property.trim());
            if (url != null) return new Resolution(url, Origin.PROPERTY, false, false);
            invalid = true;
        }
        String jarUrl = embedded == null ? null : embedded.panelUrl();
        if (jarUrl != null) {
            String mapped = normalizeLegacy(jarUrl);
            if (mapped != null) return new Resolution(mapped, Origin.JAR, invalid, !mapped.equals(jarUrl));
        }
        String fallback = builtIn == null ? null : PanelUrls.normalize(builtIn.trim());
        if (fallback != null) return new Resolution(fallback, Origin.DEFAULT, invalid, false);
        return new Resolution(null, Origin.NONE, invalid, false);
    }

    /**
     * Maps an {@code https} URL on one of the {@link #LEGACY_HOSTS} (default port) to {@link #KORDON_API}, dropping any
     * path. Anything else is returned unchanged.
     */
    public static String normalizeLegacy(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme != null && scheme.equalsIgnoreCase("https") && host != null
                    && LEGACY_HOSTS.contains(host.toLowerCase(Locale.ROOT)) && (port == -1 || port == 443)
                    && uri.getRawUserInfo() == null) {
                return KORDON_API;
            }
        } catch (URISyntaxException e) {
            // Not a URI: return it unchanged; the caller validated it already.
        }
        return url;
    }

    /**
     * The base of the pages people open: {@link #KORDON_PANEL} for the hosted API (or null), otherwise the API address
     * itself.
     */
    public static String pagesUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return KORDON_PANEL;
        String url = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return url.equalsIgnoreCase(KORDON_API) ? KORDON_PANEL : url;
    }

    /** The server's Plugin page in the panel, or the pages base when the id is null. */
    public static String pluginPage(String apiUrl, String serverId) {
        String pages = pagesUrl(apiUrl);
        return serverId == null || serverId.isBlank() ? pages : pages + "/servers/" + serverId + "/plugin";
    }
}
