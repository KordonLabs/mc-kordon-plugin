package com.kordonlabs.plugin.common.install;

import java.net.URI;

/**
 * The Kordon API address and the server key in use. The address is known even without a key, so that
 * {@code /kordon link} knows where to go. Replaced as a whole on {@code /kordon link} and {@code unlink}.
 *
 * @param apiUrl the address requests go to, valid and normalised; null only in a broken build
 * @param token  the server key; null when not linked
 * @param source where the key comes from ({@link KeySource#NONE} without one)
 */
public record Credentials(String apiUrl, String token, KeySource source) {

    public static final Credentials NONE = new Credentials(null, null, KeySource.NONE);

    public Credentials {
        if (source == null || token == null) source = token == null ? KeySource.NONE : KeySource.JAR;
    }

    /** A key built into the jar ({@link KeySource#JAR}), or {@link KeySource#NONE} without a key. */
    public Credentials(String apiUrl, String token) {
        this(apiUrl, token, token == null ? KeySource.NONE : KeySource.JAR);
    }

    /** Both values present; otherwise the plugin is NOT_CONFIGURED (no network calls, players join unchecked). */
    public boolean configured() {
        return apiUrl != null && token != null;
    }

    /** A short, loggable prefix of the key such as {@code kmc_ab12cd}; null when there is no key. */
    public String tokenPrefix() {
        return Tokens.prefix(token);
    }

    /** An {@code http://} address with a public host, so the key would travel unencrypted. */
    public boolean plainHttpPublic() {
        if (apiUrl == null || !PanelUrls.plainHttp(apiUrl)) return false;
        try {
            return !PanelUrls.isPrivateHost(URI.create(apiUrl));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "Credentials[apiUrl=" + apiUrl + ", token=" + tokenPrefix() + "…, source=" + source + "]";
    }
}
