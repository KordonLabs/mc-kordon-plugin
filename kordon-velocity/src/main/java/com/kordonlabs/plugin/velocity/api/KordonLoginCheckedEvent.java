package com.kordonlabs.plugin.velocity.api;

import com.velocitypowered.api.proxy.Player;
import com.kordonlabs.plugin.common.login.LoginVerdict;
import com.kordonlabs.plugin.common.text.Legacy;
import java.util.List;
import java.util.Objects;

/**
 * Fired when Kordon has decided about a login and is about to apply the decision. It mirrors the BungeeCord event
 * {@code com.kordonlabs.plugin.bungee.api.KordonLoginCheckedEvent}. Fired on a {@code Kordon-Login} thread after every
 * panel or cached verdict, but not when the 3.5 s login deadline expires first.
 *
 * <pre>{@code
 * // velocity-plugin.json: "dependencies": [{"id": "kordon", "optional": true}]
 * @Subscribe
 * public void onChecked(KordonLoginCheckedEvent e) {
 *     if (e.isBlocked() && trusted(e.getPlayer().getUniqueId())) e.setCancelled(true);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code setCancelled(true)} on a block lets the player in (Kordon logs that another plugin allowed the login);
 *       on an allowed login it changes nothing.</li>
 *   <li>{@link #setKickMessage(String)} changes the kick screen of a block.</li>
 * </ul>
 *
 * <p>Kordon waits for handlers at most until the login's 3.5 s deadline; a change made later is ignored, and the
 * panel's "If the check is unavailable" fallback may already have been applied. Do not block in an
 * {@code async = false} handler: it runs on Kordon's login thread. A handler that throws is logged by Velocity and does
 * not stop the others. Handlers may run on any thread.
 */
public final class KordonLoginCheckedEvent {

    private final Player player;
    private final LoginVerdict verdict;
    private volatile String kickMessage;
    private volatile boolean cancelled;

    public KordonLoginCheckedEvent(Player player, LoginVerdict verdict) {
        this.player = player;
        this.verdict = verdict;
        this.kickMessage = verdict.kickMessage();
    }

    /**
     * The joining player (name, UUID, address, protocol). It is not registered yet: {@code ProxyServer.getPlayer(uuid)}
     * is empty, and messages cannot be sent to it.
     */
    public Player getPlayer() {
        return player;
    }

    /** Kordon is about to refuse this login. */
    public boolean isBlocked() {
        return !verdict.allow();
    }

    /**
     * The panel's {@code decidedBy} ("antivpn", "blacklist", "whitelist", "country", "kordon_base", "clean",
     * "error"); may be null.
     */
    public String getDecidedBy() {
        return verdict.decidedBy();
    }

    /**
     * The panel's raw reason codes; never null. Besides "vpn", "proxy", "tor", "hosting", "suspicious", "country",
     * "blacklist", "whitelist", "check_failed" … they include the Kordon-wide codes {@code kordon_base},
     * {@code kordon_base:<category>} (vpn, proxy, tor, hosting, bot, fraud, cheat, abuse, other),
     * {@code kordon_base_auto} / {@code kordon_base_auto:<type>} and {@code previously_detected} /
     * {@code previously_detected:<type>}. Kordon's own console and chat messages describe them in words instead.
     */
    public List<String> getReasons() {
        return verdict.reasons().stream().filter(Objects::nonNull).toList();
    }

    /** Watch-only mode: the player is let in, but protection would have blocked them. */
    public boolean isWouldBlock() {
        return verdict.wouldBlock();
    }

    /**
     * How the verdict was reached: {@code PANEL}, {@code REUSED}, {@code FAIL_OPEN}, {@code FAIL_CLOSED},
     * {@code ACCOUNT_DISABLED} or {@code NOT_CONFIGURED}.
     */
    public String getSource() {
        return verdict.source().name();
    }

    /** The kick screen of a block ({@code §}-codes); null for an allowed login. */
    public String getKickMessage() {
        return kickMessage;
    }

    /** Replaces the kick screen of a block ({@code §}- or {@code &}-codes); null keeps Kordon's text. */
    public void setKickMessage(String legacy) {
        this.kickMessage = legacy == null ? null : Legacy.translate(legacy);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancelling a block lets the player in. */
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public String toString() {
        return "KordonLoginCheckedEvent{name=" + player.getUsername() + ", blocked=" + isBlocked() + ", source="
                + getSource() + ", cancelled=" + cancelled + "}";
    }
}
