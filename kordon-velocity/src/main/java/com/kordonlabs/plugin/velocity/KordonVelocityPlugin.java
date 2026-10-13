package com.kordonlabs.plugin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.install.DuplicateJarCheck;
import com.kordonlabs.plugin.common.login.HeldLogin;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.velocity.command.VelocityKordonCommand;
import com.kordonlabs.plugin.velocity.listener.LoginListener;
import com.kordonlabs.plugin.velocity.listener.PlayerListener;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kordon's entry point on Velocity 3.4+ and 4.x. It ships in the same jar as the BungeeCord plugin and is loaded from
 * {@code velocity-plugin.json}.
 *
 * <p>This class, its fields and its method signatures mention only Velocity, JDK and Kordon common/velocity types,
 * because Guice and the event manager reflect over every declared member. {@code com.google.inject.Inject} is used
 * because Guice 7 ignores {@code javax.inject.Inject}.
 *
 * <p>Listeners and {@code /kordon} are registered only when the core has {@linkplain KordonCore#started() started}.
 * If enabling fails, nothing stays registered and the proxy runs unprotected but otherwise untouched.
 */
public final class KordonVelocityPlugin {

    private static final Duration FLUSH_BUDGET = Duration.ofSeconds(2);

    private final ProxyServer proxy;
    private final PluginContainer container;
    private final Path dataDirectory;
    private volatile KordonCore core;
    private volatile LoginListener login;
    private volatile PlayerListener players;
    private volatile CommandMeta command;

    @Inject
    public KordonVelocityPlugin(ProxyServer proxy, PluginContainer container, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.container = container;
        this.dataDirectory = dataDirectory;
    }

    /** Fired before the proxy binds, so nobody is online yet. */
    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        enable();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        disable();
    }

    /** Floodgate through its own class loader; NONE when absent or not yet created. */
    static FloodgateHook floodgate(PluginManager plugins) {
        return plugins.getPlugin("floodgate")
                .flatMap(PluginContainer::getInstance)
                .map(instance -> FloodgateHook.of(instance.getClass().getClassLoader()))
                .orElse(FloodgateHook.NONE);
    }

    void enable() {
        Messages texts = null;
        Logger log = Logger.getLogger("Kordon");
        try {
            texts = Messages.load();
            FloodgateHook floodgate = floodgate(proxy.getPluginManager());
            VelocityPlatform platform = new VelocityPlatform(proxy, container, dataDirectory, log, floodgate);
            DuplicateJarCheck.run(platform.pluginsFolder(), platform.pluginJar(), log, texts);

            KordonCore c = new KordonCore(platform);
            core = c;
            c.start(); // never throws; a failed start is logged and leaves the core inactive
            if (!c.started()) return;

            LoginListener l = new LoginListener(HeldLogin.coreOf(c, platform::fireLoginChecked), floodgate, c.log(),
                    c.messages());
            proxy.getEventManager().register(this, l);
            login = l;
            PlayerListener pl = new PlayerListener(c, platform, c.log());
            proxy.getEventManager().register(this, pl);
            players = pl;
            CommandMeta meta = proxy.getCommandManager().metaBuilder("kordon").plugin(this).build();
            proxy.getCommandManager().register(meta, new VelocityKordonCommand(c::commands, c.messages(), c.log()));
            command = meta;
            // A no-op in practice: Velocity binds after the init, so nobody is online yet.
            c.listenersReady();
        } catch (Throwable t) {
            try {
                String line = texts == null ? "Kordon failed to enable (" + t + ")"
                        : texts.console(MessageKey.LOG_START_FAILED, "error", String.valueOf(t));
                log.log(Level.SEVERE, line, t);
            } catch (Throwable ignored) {
                // nothing left to report with
            }
            unregisterAll();
            KordonCore c = core;
            if (c != null) stopQuietly(c, Duration.ZERO);
            core = null;
        }
    }

    void disable() {
        KordonCore c = core;
        core = null;
        if (c != null) stopQuietly(c, FLUSH_BUDGET);
        unregisterAll();
    }

    /** Unregisters each listener and the command independently; never throws. */
    private void unregisterAll() {
        LoginListener l = login;
        login = null;
        if (l != null) {
            try {
                proxy.getEventManager().unregisterListener(this, l);
            } catch (Throwable ignored) {
                // the proxy is going down
            }
        }
        PlayerListener p = players;
        players = null;
        if (p != null) {
            try {
                proxy.getEventManager().unregisterListener(this, p);
            } catch (Throwable ignored) {
                // the proxy is going down
            }
        }
        CommandMeta m = command;
        command = null;
        if (m != null) {
            try {
                proxy.getCommandManager().unregister(m);
            } catch (Throwable ignored) {
                // the proxy is going down
            }
        }
    }

    private void stopQuietly(KordonCore c, Duration budget) {
        try {
            c.stop(budget);
        } catch (Throwable t) {
            Logger.getLogger("Kordon").log(Level.WARNING, "Kordon stop failed", t);
        }
    }
}
