package com.kordonlabs.plugin.bungee;

import com.kordonlabs.plugin.bungee.command.BungeeKordonCommand;
import com.kordonlabs.plugin.bungee.compat.BrandCache;
import com.kordonlabs.plugin.bungee.compat.Reflect;
import com.kordonlabs.plugin.bungee.compat.SkinReader;
import com.kordonlabs.plugin.bungee.listener.BrandListener;
import com.kordonlabs.plugin.bungee.listener.LoginListener;
import com.kordonlabs.plugin.bungee.listener.PlayerListener;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.install.DuplicateJarCheck;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;

/**
 * Kordon's entry point on BungeeCord / Waterfall / XCord. Listeners and {@code /kordon} are registered only after the
 * core has {@linkplain KordonCore#started() started}. If enabling fails, nothing stays registered: the proxy runs
 * unprotected but otherwise untouched.
 */
public final class KordonBungeePlugin extends Plugin {

    private static final Duration FLUSH_BUDGET = Duration.ofSeconds(2);

    private volatile KordonCore core;
    private volatile BrandCache brands;

    @Override
    public void onEnable() {
        KordonCore started = null;
        Messages texts = null;
        try {
            Reflect.probe(); // resolves the version guards once, before anything uses them
            texts = Messages.load();
            DuplicateJarCheck.run(getProxy().getPluginsFolder().toPath(), getFile().toPath(), getLogger(), texts);

            BrandCache brandCache = new BrandCache();
            brands = brandCache;
            FloodgateHook floodgate = floodgate(getProxy().getPluginManager());
            BungeePlatform platform = new BungeePlatform(this, brandCache, floodgate);
            KordonCore c = new KordonCore(platform);
            core = c;
            c.start(); // never throws; a failed start is logged and leaves the core inactive
            if (!c.started()) return;
            started = c;
            register(c, platform, brandCache, floodgate);
            // After registration, so a player who logged in meanwhile is in getPlayers() or seen by PlayerListener.
            c.listenersReady();
        } catch (Throwable t) {
            try {
                String line = texts == null ? "Kordon failed to enable (" + t + ")"
                        : texts.console(MessageKey.LOG_START_FAILED, "error", String.valueOf(t));
                getLogger().log(Level.SEVERE, line, t);
            } catch (Throwable ignored) {
                // nothing left to report with
            }
            unregisterAll();
            if (started != null) stopQuietly(started, Duration.ZERO);
            core = null;
        }
    }

    @Override
    public void onDisable() {
        KordonCore c = core;
        core = null;
        if (c != null) stopQuietly(c, FLUSH_BUDGET);
        unregisterAll();
        BrandCache cache = brands;
        brands = null;
        if (cache != null) cache.clear();
    }

    /** Floodgate through its own class loader (the soft dependency enables it first); NONE when absent. */
    static FloodgateHook floodgate(PluginManager plugins) {
        Plugin floodgate = plugins.getPlugin("floodgate");
        return floodgate == null ? FloodgateHook.NONE : FloodgateHook.of(floodgate.getClass().getClassLoader());
    }

    private void register(KordonCore c, BungeePlatform platform, BrandCache brandCache, FloodgateHook floodgate) {
        PluginManager plugins = getProxy().getPluginManager();
        ThrottledLog log = c.log();
        plugins.registerListener(this, new LoginListener(this, c, platform, new SkinReader(log), floodgate));
        plugins.registerListener(this, new PlayerListener(c, platform, brandCache, log));
        if (Reflect.CLIENT_BRAND == null) plugins.registerListener(this, new BrandListener(c, brandCache, log));
        plugins.registerCommand(this, new BungeeKordonCommand(c::commands, c.messages(), log));
    }

    private void unregisterAll() {
        try {
            getProxy().getPluginManager().unregisterListeners(this);
            getProxy().getPluginManager().unregisterCommands(this);
        } catch (Throwable ignored) {
            // the proxy is going down
        }
    }

    private void stopQuietly(KordonCore c, Duration budget) {
        try {
            c.stop(budget);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Kordon stop failed", t);
        }
    }
}
