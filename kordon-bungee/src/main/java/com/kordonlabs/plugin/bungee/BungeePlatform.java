package com.kordonlabs.plugin.bungee;

import com.kordonlabs.plugin.bungee.api.KordonLoginCheckedEvent;
import com.kordonlabs.plugin.bungee.api.KordonRemoteKickEvent;
import com.kordonlabs.plugin.bungee.compat.BrandCache;
import com.kordonlabs.plugin.bungee.compat.BungeeChannels;
import com.kordonlabs.plugin.bungee.text.BungeeText;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.login.LoginVerdict;
import com.kordonlabs.plugin.common.platform.KickCause;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.platform.PlayerFacts;
import com.kordonlabs.plugin.common.text.LocalizedLine;
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
import java.util.logging.Logger;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * The core's view of a BungeeCord / Waterfall / XCord proxy. Every method is thread-safe: the proxy's player list,
 * permission checks, {@code sendMessage}/{@code disconnect} and {@code PluginManager#callEvent} may be used from the
 * core's threads.
 */
public final class BungeePlatform implements Platform {

    private final Plugin plugin;
    private final ProxyServer proxy;
    private final BrandCache brands;
    private final FloodgateHook floodgate;

    public BungeePlatform(Plugin plugin, BrandCache brands, FloodgateHook floodgate) {
        this.plugin = plugin;
        this.proxy = plugin.getProxy();
        this.brands = brands;
        this.floodgate = floodgate;
    }

    /** {@code getName()} lower-cased, stripped to {@code [a-z0-9_-]}, at most 32 characters ("bungeecord", "xcord"). */
    @Override
    public String platformId() {
        String id = proxy.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty()) id = "bungeecord";
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    @Override
    public String serverVersion() {
        String v = proxy.getVersion();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean onlineMode() {
        return proxy.getConfig().isOnlineMode();
    }

    @Override
    public int onlineCount() {
        return proxy.getOnlineCount();
    }

    @Override
    public Path dataFolder() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public Path pluginJar() {
        return plugin.getFile().toPath();
    }

    @Override
    public Path pluginsFolder() {
        return proxy.getPluginsFolder().toPath();
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    /** Wraps {@code player}; null for null or a non-TCP connection (not tracked). */
    public BungeeOnlinePlayer wrap(ProxiedPlayer player) {
        if (player == null || BungeeChannels.isFake(player)) return null;
        return BungeeOnlinePlayer.of(player, brands, floodgate);
    }

    @Override
    public Collection<OnlinePlayer> onlinePlayers() {
        List<OnlinePlayer> out = new ArrayList<>();
        for (ProxiedPlayer player : proxy.getPlayers()) {
            OnlinePlayer wrapped = wrap(player);
            if (wrapped != null) out.add(wrapped);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public Optional<OnlinePlayer> player(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(wrap(proxy.getPlayer(uuid)));
    }

    /** Case-insensitive, like the proxy's own player map. */
    @Override
    public Optional<OnlinePlayer> player(String name) {
        return name == null || name.isEmpty() ? Optional.empty() : Optional.ofNullable(wrap(proxy.getPlayer(name)));
    }

    @Override
    public void notifyStaff(String permission, String legacySection) {
        BaseComponent line = null;
        for (ProxiedPlayer player : proxy.getPlayers()) {
            if (!player.hasPermission(permission)) continue;
            if (line == null) line = BungeeText.chat(legacySection); // no auto-link for a nick like "evil.ru/x"
            player.sendMessage(line);
        }
    }

    /** Each recipient gets the line in their client's language; each distinct component is built once. */
    @Override
    public void notifyStaff(String permission, LocalizedLine line) {
        if (!line.localized()) {
            notifyStaff(permission, line.fallback());
            return;
        }
        Map<String, BaseComponent> built = new HashMap<>();
        for (ProxiedPlayer player : proxy.getPlayers()) {
            if (!player.hasPermission(permission)) continue;
            String text = line.forLocale(PlayerFacts.localeTag(player.getLocale()));
            player.sendMessage(built.computeIfAbsent(text, BungeeText::chat));
        }
    }

    @Override
    public boolean fireRemoteKick(OnlinePlayer player, KickCause cause, String legacySection) {
        return fireRemoteKickMessage(player, cause, legacySection) != null;
    }

    /** Fires {@link KordonRemoteKickEvent}; null if a plugin cancelled it, otherwise the (possibly changed) text. */
    @Override
    public String fireRemoteKickMessage(OnlinePlayer player, KickCause cause, String legacySection) {
        ProxiedPlayer target = player instanceof BungeeOnlinePlayer wrapped ? wrapped.handle() : null;
        if (target == null) return legacySection; // not one of our proxy players, so there is no event to fire
        KordonRemoteKickEvent event = new KordonRemoteKickEvent(target, cause.name(), legacySection);
        try {
            proxy.getPluginManager().callEvent(event);
        } catch (Throwable t) {
            return legacySection; // a broken handler never stops a kick
        }
        if (event.isCancelled()) return null;
        String message = event.getMessage();
        return message == null ? legacySection : message;
    }

    /** Fires {@link KordonLoginCheckedEvent} and returns the verdict as the handlers left it. */
    @Override
    public LoginVerdict fireLoginChecked(Object connectionKey, LoginVerdict verdict) {
        if (verdict == null || !(connectionKey instanceof PendingConnection connection)) return verdict;
        KordonLoginCheckedEvent event = new KordonLoginCheckedEvent(connection, verdict);
        try {
            proxy.getPluginManager().callEvent(event);
        } catch (Throwable t) {
            return verdict; // the proxy logs handler exceptions itself; this catches anything else
        }
        return LoginVerdict.afterPublicEvent(verdict, event.isCancelled(), event.getKickMessage());
    }
}
