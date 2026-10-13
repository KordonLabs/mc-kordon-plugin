package com.kordonlabs.plugin.common.platform;

/** Why the panel asked to disconnect a player. */
public enum KickCause {
    /** An admin kicked the player from the panel. */
    PANEL,
    /** A blacklist entry matches the player (added in the panel, or found at the join-time check). */
    BLACKLIST,
    /** {@code /kordon blacklist add} in the game or the console. */
    GAME;

    /** Parses the wire value; unknown values map to {@link #PANEL}. */
    public static KickCause fromWire(String reason) {
        if ("blacklist".equals(reason)) return BLACKLIST;
        if ("game".equals(reason)) return GAME;
        return PANEL;
    }
}
