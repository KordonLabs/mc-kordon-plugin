package com.kordonlabs.plugin.velocity.api;

import com.velocitypowered.api.proxy.Player;
import com.kordonlabs.plugin.common.text.Legacy;

/**
 * Fired when Kordon is about to disconnect an online player on the panel's order. It mirrors the BungeeCord event
 * {@code com.kordonlabs.plugin.bungee.api.KordonRemoteKickEvent}. Fired on the {@code Kordon-Events} thread before the
 * kick; Kordon waits for handlers at most 2 s, ignores later changes, and still kicks if a handler fails.
 *
 * <ul>
 *   <li>{@link #getCause()}: {@code PANEL} (the panel's kick action), {@code BLACKLIST} (a blacklist entry matches the
 *       player), {@code GAME} ({@code /kordon blacklist add}).</li>
 *   <li>{@code setCancelled(true)} skips the kick; Kordon does not retry it for this session.</li>
 *   <li>{@link #setMessage(String)} changes the kick screen.</li>
 * </ul>
 *
 * <p>Do not block in an {@code async = false} handler: it runs on Kordon's thread. Handlers may run on any thread.
 */
public final class KordonRemoteKickEvent {

    private final Player player;
    private final String cause;
    private volatile String message;
    private volatile boolean cancelled;

    public KordonRemoteKickEvent(Player player, String cause, String message) {
        this.player = player;
        this.cause = cause;
        this.message = message;
    }

    public Player getPlayer() {
        return player;
    }

    /** {@code PANEL}, {@code BLACKLIST} or {@code GAME}. */
    public String getCause() {
        return cause;
    }

    /** The kick screen ({@code §}-codes). */
    public String getMessage() {
        return message;
    }

    /** Replaces the kick screen ({@code §}- or {@code &}-codes); null keeps Kordon's text. */
    public void setMessage(String legacy) {
        this.message = legacy == null ? null : Legacy.translate(legacy);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling skips the kick. */
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public String toString() {
        return "KordonRemoteKickEvent{player=" + (player == null ? null : player.getUsername()) + ", cause=" + cause
                + ", cancelled=" + cancelled + "}";
    }
}
