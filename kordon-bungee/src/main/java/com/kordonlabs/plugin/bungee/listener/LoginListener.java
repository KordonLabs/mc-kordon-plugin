package com.kordonlabs.plugin.bungee.listener;

import com.kordonlabs.plugin.bungee.compat.AsyncEvents;
import com.kordonlabs.plugin.bungee.compat.BungeeChannels;
import com.kordonlabs.plugin.bungee.compat.SkinReader;
import com.kordonlabs.plugin.bungee.text.BungeeText;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.login.HeldLogin;
import com.kordonlabs.plugin.common.login.LoginAttempt;
import com.kordonlabs.plugin.common.login.Skin;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.platform.PlayerFacts;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Holds every login until Kordon has answered.
 *
 * <p>Priority {@code HIGH} runs after plugins that decide at NORMAL (an already cancelled login costs no
 * {@code /login} request) and before HIGHEST monitors. The proxy's thread does only O(1) work: everything fallible
 * happens <em>before</em> {@code registerIntent}, the hold returns at once, and the verdict is applied later on
 * Kordon's login threads. A throwable between {@code registerIntent} and a successful hold applies the fallback
 * verdict and completes the intent at once.
 */
public final class LoginListener implements Listener {

    /**
     * How long a release waits for the proxy's {@code postCall()}, which normally takes microseconds. Only another
     * plugin's handler blocking the proxy's thread longer makes it give up and complete anyway.
     */
    static final long POST_CALL_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Plugin plugin;
    private final HeldLogin.Core core;
    private final SkinReader skins;
    private final FloodgateHook floodgate;
    private final ThrottledLog log;
    private final Messages messages;
    private final AtomicBoolean notInetLogged = new AtomicBoolean();

    /**
     * Expects a started {@code core}. BungeeCord's public event is fired synchronously on the {@code Kordon-Login-*}
     * thread, so the hold's deadline is not needed there.
     */
    public LoginListener(Plugin plugin, KordonCore core, Platform platform, SkinReader skins, FloodgateHook floodgate) {
        this(plugin, HeldLogin.coreOf(core, (key, verdict, deadline) -> platform.fireLoginChecked(key, verdict)),
                skins, floodgate, core.log(), core.messages());
    }

    LoginListener(Plugin plugin, HeldLogin.Core core, SkinReader skins, FloodgateHook floodgate, ThrottledLog log,
                  Messages messages) {
        this.plugin = plugin;
        this.core = core;
        this.skins = skins;
        this.floodgate = floodgate;
        this.log = log;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(LoginEvent event) {
        try {
            check(event);
        } catch (Throwable t) {
            // Failed before registerIntent: the proxy goes on without Kordon's verdict for this login.
            log.warn("listener.login", "Kordon: login check skipped after an error: " + t);
        }
    }

    private void check(LoginEvent event) {
        if (event.isCancelled()) return; // already refused by another plugin
        HeldLogin.Gate gate = core.gate();
        if (gate == null) return;
        PendingConnection connection = event.getConnection();
        if (BungeeChannels.isFake(connection)) return;
        String name = connection.getName();
        String ip = PlayerFacts.ipOf(connection.getSocketAddress());
        if (ip == null) {
            if (notInetLogged.compareAndSet(false, true)) {
                log.warn(messages.console(MessageKey.LOG_NOT_INET, "name", Messages.literal(name)));
            }
            return;
        }
        UUID uuid = connection.getUniqueId();
        boolean bedrock = floodgate.isBedrock(uuid);
        Skin skin = bedrock ? null : skins.read(connection);
        LoginAttempt attempt = new LoginAttempt(connection, name, uuid, ip, connection.getVersion(), skin, bedrock,
                PlayerFacts.joinHost(connection.getVirtualHost()));
        Target target = new Target(event, attempt, gate);

        event.registerIntent(plugin); // the last fallible step before the hold
        try {
            gate.hold(attempt, target);
        } catch (Throwable t) {
            target.fail(t);
        }
    }

    /**
     * The Bungee side of {@link HeldLogin}: a block cancels the event and the release completes the intent. A release
     * from a thread other than the proxy's first waits until the proxy has called {@code postCall()}, since otherwise
     * Bungee can lose the login callback (see {@link AsyncEvents}).
     */
    private final class Target extends HeldLogin {

        private final LoginEvent event;
        /** The proxy's thread dispatching {@code event}; it calls {@code postCall()} after the handlers. */
        private final Thread dispatcher = Thread.currentThread();

        Target(LoginEvent event, LoginAttempt attempt, HeldLogin.Gate gate) {
            super(attempt, gate, LoginListener.this.core, LoginListener.this.log);
            this.event = event;
        }

        @Override
        protected void deny(String kickMessage) {
            BungeeText.denyLogin(event, kickMessage); // setCancelled(true) first
        }

        @Override
        protected void resume() {
            // On the proxy's own thread (a verdict inside hold()) postCall() has not started and runs the callback
            // itself; on a Kordon thread, completeIntent must not overlap postCall().
            if (Thread.currentThread() != dispatcher) AsyncEvents.awaitPostCall(event, POST_CALL_WAIT_NANOS);
            event.completeIntent(plugin); // the proxy continues the login on this thread
        }
    }
}
