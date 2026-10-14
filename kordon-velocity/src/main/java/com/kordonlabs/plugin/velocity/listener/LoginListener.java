package com.kordonlabs.plugin.velocity.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.login.HeldLogin;
import com.kordonlabs.plugin.common.login.LoginAttempt;
import com.kordonlabs.plugin.common.login.Skin;
import com.kordonlabs.plugin.common.platform.PlayerFacts;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import com.kordonlabs.plugin.velocity.VelocitySkins;
import com.kordonlabs.plugin.velocity.compat.VelocityChannels;
import com.kordonlabs.plugin.velocity.text.VelocityText;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.kyori.adventure.text.Component;

/**
 * Holds every login until Kordon has answered. {@code LoginEvent} is handled at {@code LATE} (after plugins that
 * decide at NORMAL, before LAST monitors) and returns a continuation task that Velocity waits on.
 *
 * <p>The handler usually runs on a Netty I/O thread, so it does only constant-time work. Everything fallible happens
 * in {@code check()} before the task exists; an error there lets the login continue unchecked. Inside the continuation
 * every error goes to {@code held.fail(t)}, which applies the "If the check is unavailable" fallback and resumes
 * exactly once, because Velocity treats a throwing consumer as a resume and would let the login through.
 */
@SuppressWarnings("deprecation") // @Subscribe(order = ...) exists on every Velocity 3.x/4.x; priority is 3.3+ only
public final class LoginListener {

    private final HeldLogin.Core core;
    private final FloodgateHook floodgate;
    private final ThrottledLog log;
    private final Messages messages;
    private final Function<String, Component> text;
    private final VelocitySkins skins;
    /** The denial used when the kick text cannot be built, so a refusal never depends on its text. */
    private final ResultedEvent.ComponentResult fallbackDenial;
    private final AtomicBoolean notInetLogged = new AtomicBoolean();

    public LoginListener(HeldLogin.Core core, FloodgateHook floodgate, ThrottledLog log, Messages messages) {
        this(core, floodgate, log, messages, VelocityText::of);
    }

    LoginListener(HeldLogin.Core core, FloodgateHook floodgate, ThrottledLog log, Messages messages,
                  Function<String, Component> text) {
        this.core = core;
        this.floodgate = floodgate;
        this.log = log;
        this.messages = messages;
        this.text = text;
        this.skins = new VelocitySkins(log);
        this.fallbackDenial = ResultedEvent.ComponentResult.denied(Component.text(""));
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onLogin(LoginEvent event) {
        try {
            return check(event);
        } catch (Throwable t) {
            // No task yet: Velocity continues this login without Kordon's verdict.
            log.warn("listener.login", "Kordon: login check skipped after an error: " + t);
            return null;
        }
    }

    private EventTask check(LoginEvent event) {
        if (!event.getResult().isAllowed()) return null; // already denied by another plugin
        HeldLogin.Gate gate = core.gate();
        if (gate == null) return null; // not started, not configured or stopping
        Player player = event.getPlayer();
        if (VelocityChannels.isFake(player)) return null;
        String ip = PlayerFacts.ipOf(player.getRemoteAddress());
        if (ip == null) {
            if (notInetLogged.compareAndSet(false, true)) {
                log.warn(messages.console(MessageKey.LOG_NOT_INET, "name", Messages.literal(player.getUsername())));
            }
            return null;
        }
        UUID uuid = player.getUniqueId();
        boolean bedrock = floodgate.isBedrock(uuid);
        Skin skin = bedrock ? null : skins.read(player); // online mode only, never for Bedrock players
        LoginAttempt attempt = new LoginAttempt(player, player.getUsername(), uuid, ip,
                player.getProtocolVersion().getProtocol(), skin, bedrock,
                PlayerFacts.joinHost(player.getVirtualHost().orElse(null)));
        VelocityHeldLogin held = new VelocityHeldLogin(event, attempt, gate);
        return EventTask.withContinuation(continuation -> {
            try {
                held.attach(continuation);  // must happen before anything can release the login
                gate.hold(attempt, held);   // returns at once; the verdict is applied and released exactly once
            } catch (Throwable t) {
                held.fail(t);               // applies the fallback verdict and resumes exactly once
            }
        });
    }

    /** The Velocity side of {@link HeldLogin}: a block sets a denied result, the release resumes the continuation. */
    final class VelocityHeldLogin extends HeldLogin {

        private final LoginEvent event;
        /** Attached before {@code hold()}, so {@link #resume()} always finds it. */
        private volatile Continuation continuation;

        VelocityHeldLogin(LoginEvent event, LoginAttempt attempt, HeldLogin.Gate gate) {
            super(attempt, gate, LoginListener.this.core, LoginListener.this.log);
            this.event = event;
        }

        void attach(Continuation c) {
            continuation = c;
        }

        /**
         * Sets the pre-built denial first and only then the real text, so a failure while building the text cannot
         * turn the refusal into an allow. Never {@code denied(null)}: Velocity recognises a denial by its reason
         * component.
         */
        @Override
        protected void deny(String kickMessage) {
            event.setResult(fallbackDenial);
            try {
                event.setResult(ResultedEvent.ComponentResult.denied(text.apply(kickMessage)));
            } catch (Throwable t) {
                log.warn("listener.login.text", "Kordon: kick text not built, the login is still refused: " + t);
            }
        }

        /** {@link HeldLogin} guarantees a single call; a second resume would throw inside Velocity. */
        @Override
        protected void resume() {
            Continuation c = continuation;
            if (c != null) c.resume();
        }
    }
}
