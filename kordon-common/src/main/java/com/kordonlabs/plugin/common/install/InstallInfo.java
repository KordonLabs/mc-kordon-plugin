package com.kordonlabs.plugin.common.install;

/**
 * The {@code kordon-install.json} embedded in a personal Kordon.jar, already validated by {@link InstallReader}; an
 * invalid entry is read as {@link #NONE}.
 *
 * @param panelUrl   the Kordon API address of the download, trailing {@code /} stripped
 * @param token      the server key
 * @param serverId   the panel's server id
 * @param serverName the panel's server name, with {@code \\uXXXX} escapes already decoded
 * @param issuedAt   ISO-8601 time of the download
 */
public record InstallInfo(String panelUrl, String token, String serverId, String serverName, String issuedAt) {

    /** An absent, unreadable or invalid entry. */
    public static final InstallInfo NONE = new InstallInfo(null, null, null, null, null);

    /** Both values usable. */
    public boolean present() {
        return panelUrl != null && token != null;
    }

    /**
     * Identity of this download: {@code issuedAt} plus the token prefix. {@code /kordon reload} compares it with the
     * one read at start; a difference means a new Kordon.jar was copied over the running one.
     */
    public String fingerprint() {
        if (!present()) return "";
        return issuedAt + "|" + token.substring(0, Math.min(Tokens.PREFIX_LENGTH, token.length()));
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "InstallInfo[panelUrl=" + panelUrl + ", token=" + (token == null ? null : "***") + ", serverId="
                + serverId + ", issuedAt=" + issuedAt + "]";
    }
}
