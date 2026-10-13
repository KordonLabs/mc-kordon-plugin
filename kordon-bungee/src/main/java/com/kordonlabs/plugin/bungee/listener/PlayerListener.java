package com.kordonlabs.plugin.bungee.listener;

import com.kordonlabs.plugin.bungee.BungeeOnlinePlayer;
import com.kordonlabs.plugin.bungee.BungeePlatform;
import com.kordonlabs.plugin.bungee.compat.BrandCache;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.login.LoginGate;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.SettingsChangedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Tracks sessions: join, backend, client settings and quit. All handlers are read-only and O(1) and never throw into
 * the proxy. Everything is keyed by the player's {@code PendingConnection}, the same object the login was checked
 * with, so an online-mode reconnect of the same account never touches the older connection's session.
 *
 * <p>Joins are tracked only while Kordon is active; other updates reach the tracker whenever it exists, and it ignores
 * connections it does not know.
 */
public final class PlayerListener implements Listener {

    private final KordonCore core;
    private final BungeePlatform platform;
    private final BrandCache brands;
    private final ThrottledLog log;

    public PlayerListener(KordonCore core, BungeePlatform platform, BrandCache brands, ThrottledLog log) {
        this.core = core;
        this.platform = platform;
        this.brands = brands;
        this.log = log;
    }

    /** A plain {@code Event} before 1.21 and an {@code AsyncEvent} after, so never {@code registerIntent} here. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostLogin(PostLoginEvent event) {
        try {
            if (core.stopped() || !core.active()) return;
            SessionTracker sessions = core.sessions();
            LoginGate gate = core.loginGate();
            if (sessions == null || gate == null) return;
            ProxiedPlayer player = event.getPlayer();
            Long loginId = gate.takeLoginId(player.getPendingConnection());
            BungeeOnlinePlayer online = platform.wrap(player);
            if (online != null) sessions.onJoin(online, loginId);
        } catch (Throwable t) {
            failed("PostLoginEvent", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerConnected(ServerConnectedEvent event) {
        try {
            SessionTracker sessions = sessions();
            if (sessions == null) return;
            Object key = event.getPlayer().getPendingConnection();
            Server server = event.getServer();
            ServerInfo info = server == null ? null : server.getInfo();
            if (info != null) sessions.onBackend(key, info.getName());
            sessions.onClientInfoChanged(key); // the brand is usually known by now
        } catch (Throwable t) {
            failed("ServerConnectedEvent", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSettingsChanged(SettingsChangedEvent event) {
        try {
            SessionTracker sessions = sessions();
            if (sessions != null) sessions.onClientInfoChanged(event.getPlayer().getPendingConnection());
        } catch (Throwable t) {
            failed("SettingsChangedEvent", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDisconnect(PlayerDisconnectEvent event) {
        try {
            Object key = event.getPlayer().getPendingConnection();
            brands.forget(key);
            SessionTracker sessions = sessions();
            if (sessions != null) sessions.onQuit(key); // recorded as "kicked" if Kordon kicked it within 10 s
        } catch (Throwable t) {
            failed("PlayerDisconnectEvent", t);
        }
    }

    private SessionTracker sessions() {
        return core.stopped() ? null : core.sessions();
    }

    private void failed(String event, Throwable t) {
        log.warn("listener." + event, "Kordon: " + event + " not tracked after an error: " + t);
    }
}
