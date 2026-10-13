package com.kordonlabs.plugin.common.api.dto;

import java.util.UUID;

/**
 * Who typed a {@code /kordon} command; sent as {@code actor} in lists and lookup requests for the panel's audit log.
 *
 * @param type "player" | "console"
 * @param name player name (never the display name); null for the console
 * @param uuid dashed UUID (Floodgate UUIDs included); null for the console
 */
public record Actor(String type, String name, String uuid) {

    public static final String PLAYER = "player";
    public static final String CONSOLE = "console";

    public static Actor player(String name, UUID uuid) {
        return new Actor(PLAYER, name, uuid == null ? null : uuid.toString());
    }

    public static Actor console() {
        return new Actor(CONSOLE, null, null);
    }
}
