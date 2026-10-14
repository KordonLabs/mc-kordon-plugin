package com.kordonlabs.plugin.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.kordonlabs.plugin.common.KordonInfo;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.login.LoginHold;
import com.kordonlabs.plugin.common.login.LoginVerdict;
import com.kordonlabs.plugin.common.platform.KickCause;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.text.LocalizedLine;
import com.kordonlabs.plugin.common.util.SystemClock;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import com.kordonlabs.plugin.velocity.api.KordonLoginCheckedEvent;
import com.kordonlabs.plugin.velocity.compat.VelocityChannels;
import com.kordonlabs.plugin.velocity.api.KordonRemoteKickEvent;
import com.kordonlabs.plugin.velocity.text.VelocityText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;

/**
 * The core's view of a Velocity proxy. Every method is thread-safe and may be called from the core's threads.
 *
 * <p>The public events are fired with {@code fire(event).get(timeout)} on Kordon's own threads. The timeout only
 * bounds handlers that Velocity runs on their plugin's executor (the default {@code async = true}); an
 * {@code async = false} handler runs inline before {@code fire()} returns and is not bounded. The login watchdog still
 * releases the player after 3.5 s from its own thread.
 */
public final class VelocityPlatform implements Platform {

    private final ProxyServer proxy;
    private final PluginContainer container;
    private final Path dataDirectory;
    private final Logger logger;
    private final FloodgateHook floodgate;
    private final ThrottledLog log;

    /** How long a remote kick waits for {@link KordonRemoteKickEvent} handlers. */
    long remoteKickMillis = 2_000;
    /** The longest wait for {@link KordonLoginCheckedEvent} handlers; the login deadline usually comes first. */
    long loginEventMaxMillis = LoginHold.WATCHDOG_MILLIS;

    public VelocityPlatform(ProxyServer proxy, PluginContainer container, Path dataDirectory, Logger logger,
                            FloodgateHook floodgate) {
        this.proxy = proxy;
        this.container = container;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.floodgate = floodgate;
        this.log = new ThrottledLog(logger, SystemClock.INSTANCE);
    }

    /**
     * The proxy name lower-cased and reduced to {@code [a-z0-9_-]}, at most 32 characters (a fork named
     * "Velocity-CTD" gives "velocity-ctd"); "velocity" when nothing is left.
     */
    @Override
    public String platformId() {
        String name = proxy.getVersion().getName();
        String id = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty()) id = "velocity";
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    /** The proxy version string, at most 64 characters. */
    @Override
    public String serverVersion() {
        String v = proxy.getVersion().getVersion();
        if (v == null) return "";
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    @Override
    public String pluginVersion() {
        return container.getDescription().getVersion().orElse(KordonInfo.version());
    }

    @Override
    public boolean onlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public int onlineCount() {
        return proxy.getPlayerCount();
    }

    /** {@code plugins/Kordon}, as on BungeeCord, rather than Velocity's default {@code plugins/kordon}. */
    @Override
    public Path dataFolder() {
        return dataDirectory.toAbsolutePath().normalize().resolveSibling("Kordon");
    }

    /** The jar Velocity loaded (its source path may be relative), else this class's code source. */
    @Override
    public Path pluginJar() {
        Optional<Path> source = container.getDescription().getSource();
        if (source.isPresent()) return source.get().toAbsolutePath().normalize();
        try {
            return Path.of(KordonVelocityPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception | LinkageError e) {
            return pluginsFolder().resolve("Kordon.jar");
        }
    }

    @Override
    public Path pluginsFolder() {
        return dataDirectory.toAbsolutePath().normalize().getParent();
    }

    @Override
    public Logger logger() {
        return logger;
    }

    /** Wraps {@code player}; null for null or for a connection without an IP address. */
    public VelocityOnlinePlayer wrap(Player player) {
        if (player == null || VelocityChannels.isFake(player)) return null;
        return VelocityOnlinePlayer.of(player, floodgate);
    }

    @Override
    public Collection<OnlinePlayer> onlinePlayers() {
        List<OnlinePlayer> out = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            OnlinePlayer wrapped = wrap(player);
            if (wrapped != null) out.add(wrapped);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Optional<OnlinePlayer> player(UUID uuid) {
        return uuid == null ? Optional.empty() : proxy.getPlayer(uuid).<OnlinePlayer>map(this::wrap);
    }

    /** Case-insensitive, as Velocity's own player map is. */
    @Override
    public Optional<OnlinePlayer> player(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        return proxy.getPlayer(name).<OnlinePlayer>map(this::wrap);
    }

    @Override
    public void notifyStaff(String permission, String legacySection) {
        Component line = null;
        for (Player player : proxy.getAllPlayers()) {
            if (!player.hasPermission(permission)) continue;
            if (line == null) line = VelocityText.chat(legacySection); // built once, only if someone receives it
            player.sendMessage(line);
        }
    }

    /** Each recipient gets the line in their client's language; each distinct line is built once. */
    @Override
    public void notifyStaff(String permission, LocalizedLine line) {
        if (!line.localized()) {
            notifyStaff(permission, line.fallback());
            return;
        }
        Map<String, Component> built = new HashMap<>();
        for (Player player : proxy.getAllPlayers()) {
            if (!player.hasPermission(permission)) continue;
            String text = line.forLocale(VelocityOnlinePlayer.localeOf(player));
            player.sendMessage(built.computeIfAbsent(text, VelocityText::chat));
        }
    }

    @Override
    public boolean fireRemoteKick(OnlinePlayer player, KickCause cause, String legacySection) {
        return fireRemoteKickMessage(player, cause, legacySection) != null;
    }

    /**
     * Fires {@link KordonRemoteKickEvent}. Returns null if another plugin cancelled the kick, otherwise the possibly
     * changed kick text. Handlers that time out or throw never stop the kick, and Kordon's text is kept.
     */
    @Override
    public String fireRemoteKickMessage(OnlinePlayer player, KickCause cause, String legacySection) {
        Player target = player instanceof VelocityOnlinePlayer wrapped ? wrapped.handle() : null;
        if (target == null) return legacySection; // not one of our proxy players
        KordonRemoteKickEvent event = new KordonRemoteKickEvent(target, cause.name(), legacySection);
        try {
            proxy.getEventManager().fire(event).get(remoteKickMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return legacySection;
        } catch (Throwable t) {
            log.warn("event.kick", "Kordon: KordonRemoteKickEvent handlers did not answer, the kick goes ahead: " + t);
            return legacySection;
        }
        if (event.isCancelled()) return null;
        String message = event.getMessage();
        return message == null ? legacySection : message;
    }

    /** Uses the whole watchdog budget from now; the login listener passes the hold's own deadline instead. */
    @Override
    public LoginVerdict fireLoginChecked(Object connectionKey, LoginVerdict verdict) {
        return fireLoginChecked(connectionKey, verdict, System.nanoTime() + LoginHold.WATCHDOG.toNanos());
    }

    /**
     * Fires {@link KordonLoginCheckedEvent} and waits for handlers at most until {@code deadlineNanos}. Past the
     * deadline the event is not fired at all. A timeout or error leaves {@code verdict} unchanged.
     */
    public LoginVerdict fireLoginChecked(Object connectionKey, LoginVerdict verdict, long deadlineNanos) {
        if (verdict == null || !(connectionKey instanceof Player player)) return verdict;
        long left = Math.min(loginEventMaxMillis, (deadlineNanos - System.nanoTime()) / 1_000_000);
        if (left <= 0) return verdict;
        KordonLoginCheckedEvent event = new KordonLoginCheckedEvent(player, verdict);
        try {
            proxy.getEventManager().fire(event).get(left, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return verdict;
        } catch (Throwable t) {
            log.warn("event.login", "Kordon: KordonLoginCheckedEvent handlers did not answer in time, "
                    + "Kordon's verdict stays: " + t);
            return verdict;
        }
        return LoginVerdict.afterPublicEvent(verdict, event.isCancelled(), event.getKickMessage());
    }
}
