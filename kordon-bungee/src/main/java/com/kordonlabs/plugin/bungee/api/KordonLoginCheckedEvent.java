package com.kordonlabs.plugin.bungee.api;

import com.kordonlabs.plugin.common.login.LoginVerdict;
import com.kordonlabs.plugin.common.text.Legacy;
import java.util.List;
import java.util.Objects;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.plugin.Cancellable;
import net.md_5.bungee.api.plugin.Event;

/**
 * Fired when Kordon has decided about a login and is about to apply the decision. It runs on a {@code Kordon-Login}
 * thread after every panel or cached verdict, but not when the 3.5 s watchdog decides (the watchdog runs no
 * third-party code).
 *
 * <ul>
 *   <li>{@code setCancelled(true)} on a block lets the player in (Kordon logs that another plugin allowed the login);
 *       on an allowed login it changes nothing.</li>
 *   <li>{@link #setKickMessage(String)} changes the kick screen of a block.</li>
 * </ul>
 *
 * <p>Handlers must be fast and thread-safe. A handler that returns after the watchdog has fired loses its change,
 * because the panel's "If the check is unavailable" fallback has already been applied.
 */
public final class KordonLoginCheckedEvent extends Event implements Cancellable {

    private final PendingConnection connection;
    private final LoginVerdict verdict;
    private volatile String kickMessage;
    private volatile boolean cancelled;

    public KordonLoginCheckedEvent(PendingConnection connection, LoginVerdict verdict) {
        this.connection = connection;
        this.verdict = verdict;
        this.kickMessage = verdict.kickMessage();
    }

    /** The joining connection (name, UUID, address, protocol). */
    public PendingConnection getConnection() {
        return connection;
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
     * "blacklist", "whitelist", "check_failed" and others, they include the Kordon-wide codes {@code kordon_base},
     * {@code kordon_base:<category>} (vpn, proxy, tor, hosting, bot, fraud, cheat, abuse, other),
     * {@code kordon_base_auto} / {@code kordon_base_auto:<type>} and {@code previously_detected} /
     * {@code previously_detected:<type>}. Kordon's own console and chat messages show readable descriptions instead.
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

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** {@code true} on a block lets the player in. */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public String toString() {
        return "KordonLoginCheckedEvent{name=" + connection.getName() + ", blocked=" + isBlocked() + ", source="
                + getSource() + ", cancelled=" + cancelled + "}";
    }
}
