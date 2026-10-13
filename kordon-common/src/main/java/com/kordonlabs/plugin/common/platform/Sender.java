package com.kordonlabs.plugin.common.platform;

import java.util.UUID;

/** Whoever typed a {@code /kordon} command: a player or the console. Thread-safe. */
public interface Sender {

    /** The player's name (never the display name), or "console". */
    String name();

    /** Null for the console. */
    UUID uuid();

    boolean console();

    /** The sender's own permission (the proxy's console has every one; another plugin's sender only its own). */
    boolean hasPermission(String node);

    /** A chat line with {@code §} codes already translated; the console gets it without colours. Thread-safe. */
    void send(String legacySection);

    /** A clickable link for players (hover text {@code hoverLegacySection}); the console prints {@code text url}. */
    void sendLink(String legacySection, String url, String hoverLegacySection);
}
