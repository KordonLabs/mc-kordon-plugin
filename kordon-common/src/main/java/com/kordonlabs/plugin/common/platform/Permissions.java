package com.kordonlabs.plugin.common.platform;

/** Permission nodes of the plugin, shared by the core and the platform modules. */
public final class Permissions {

    /** {@code /kordon}, {@code /kordon help}, {@code /kordon status}; the command's registration permission. */
    public static final String COMMAND = "kordon.command";
    public static final String RELOAD = "kordon.command.reload";
    public static final String LOOKUP = "kordon.command.lookup";
    /** IP queries and IP lines in {@code /kordon lookup}. */
    public static final String LOOKUP_IP = "kordon.command.lookup.ip";
    public static final String WHITELIST = "kordon.command.whitelist";
    public static final String BLACKLIST = "kordon.command.blacklist";
    /**
     * {@code /kordon link} and {@code /kordon unlink}, which take the server key. Not implied by
     * {@code kordon.command}, though permission-plugin wildcards such as {@code kordon.*} include it, so grant it to
     * server owners only.
     */
    public static final String LINK = "kordon.command.link";
    /** Receives the panel's staff notifications in chat (blocked joins, and joins when enabled). */
    public static final String NOTIFY = "kordon.notify";

    private Permissions() {
    }
}
