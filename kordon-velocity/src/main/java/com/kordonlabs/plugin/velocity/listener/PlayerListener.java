package com.kordonlabs.plugin.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.login.LoginGate;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import com.kordonlabs.plugin.velocity.VelocityOnlinePlayer;
import com.kordonlabs.plugin.velocity.VelocityPlatform;

/**
 * Feeds the session tracker on Velocity: join, backend, client settings, brand and quit. Every handler runs
 * synchronously at {@code LAST}, does only constant-time work and never throws into the proxy. Everything is keyed by
 * the {@link Player} object the login was checked with.
 *
 * <p>PostLogin and Disconnect are fired separately, and when another plugin's async handler runs first they execute on
 * unordered executor threads. A client that leaves right after logging in can therefore have its quit handled before
 * its join, so {@code onPostLogin} checks the player again after the join.
 */
@SuppressWarnings("deprecation") // @Subscribe(order = ...) exists on every Velocity 3.x/4.x; priority is 3.3+ only
public final class PlayerListener {

    private final KordonCore core;
    private final VelocityPlatform platform;
    private final ThrottledLog log;

    public PlayerListener(KordonCore core, VelocityPlatform platform, ThrottledLog log) {
        this.core = core;
        this.platform = platform;
        this.log = log;
    }

    /** For protocol 764 (1.20.2) and later, Velocity fires this after LoginAcknowledged. */
    @Subscribe(order = PostOrder.LAST, async = false)
    public void onPostLogin(PostLoginEvent event) {
        try {
            if (core.stopped() || !core.active()) return;
            SessionTracker sessions = core.sessions();
            LoginGate gate = core.loginGate();
            if (sessions == null || gate == null) return;
            Player player = event.getPlayer();
            Long loginId = gate.takeLoginId(player);
            VelocityOnlinePlayer online = platform.wrap(player);
            if (online != null) sessions.onJoin(online, loginId);
            if (!player.isActive()) sessions.onQuit(player); // its disconnect may already have been handled
        } catch (Throwable t) {
            failed("PostLoginEvent", t);
        }
    }

    /** {@code getCurrentServer()} is still empty here, so the backend comes from the event. */
    @Subscribe(order = PostOrder.LAST, async = false)
    public void onServerConnected(ServerConnectedEvent event) {
        try {
            SessionTracker sessions = sessions();
            if (sessions == null) return;
            Player player = event.getPlayer();
            sessions.onBackend(player, event.getServer().getServerInfo().getName());
            sessions.onClientInfoChanged(player); // the brand is usually known by now
        } catch (Throwable t) {
            failed("ServerConnectedEvent", t);
        }
    }

    /** Velocity stores the settings before it fires this event, so they can be read from the player. */
    @Subscribe(order = PostOrder.LAST, async = false)
    public void onSettingsChanged(PlayerSettingsChangedEvent event) {
        try {
            SessionTracker sessions = sessions();
            if (sessions != null) sessions.onClientInfoChanged(event.getPlayer());
        } catch (Throwable t) {
            failed("PlayerSettingsChangedEvent", t);
        }
    }

    /** Fired before Velocity stores the brand, so it comes from the event. */
    @Subscribe(order = PostOrder.LAST, async = false)
    public void onClientBrand(PlayerClientBrandEvent event) {
        try {
            SessionTracker sessions = sessions();
            if (sessions != null) sessions.onBrand(event.getPlayer(), event.getBrand());
        } catch (Throwable t) {
            failed("PlayerClientBrandEvent", t);
        }
    }

    /**
     * Fired for every connection, including denied ones and sometimes twice. A login that never reached PostLogin is
     * dropped by the gate; the tracker ignores unknown players.
     */
    @Subscribe(order = PostOrder.LAST, async = false)
    public void onDisconnect(DisconnectEvent event) {
        try {
            Player player = event.getPlayer();
            LoginGate gate = core.loginGate();
            if (gate != null) gate.onLoginAborted(player); // a no-op once PostLogin has taken the entry
            SessionTracker sessions = sessions();
            if (sessions != null) sessions.onQuit(player); // recorded as "kicked" if Kordon kicked it within 10 s
        } catch (Throwable t) {
            failed("DisconnectEvent", t);
        }
    }

    private SessionTracker sessions() {
        return core.stopped() ? null : core.sessions();
    }

    private void failed(String event, Throwable t) {
        log.warn("listener." + event, "Kordon: " + event + " not tracked after an error: " + t);
    }
}
