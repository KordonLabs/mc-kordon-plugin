package com.kordonlabs.plugin.common.kick;

import com.kordonlabs.plugin.common.api.dto.Kick;
import com.kordonlabs.plugin.common.platform.KickCause;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.text.Legacy;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Executes the panel's kick orders from sync answers and from {@code /kordon blacklist add}.
 *
 * <p>{@link #submit} decides on the caller's thread which orders are new, since the panel repeats a kick on every
 * poll until the session ends, then runs them on the events executor. Each kick fires the public remote-kick event;
 * if another plugin cancels it, that session stays suppressed for its lifetime. A kick for an unknown session reports
 * the session as gone.
 */
public final class KickExecutor {

    /** A sid is executed at most once per this window. */
    static final Duration DEDUP = Duration.ofMinutes(5);
    /** The panel's default kick message, for an order without a text. */
    static final String DEFAULT_MESSAGE = "Вас отключил администратор сервера.";

    private final Platform platform;
    private final SessionTracker sessions;
    private final Messages messages;
    private final ThrottledLog log;
    private final Executor events;
    private final Clock clock;

    /** sid → {@code Clock.nanos()} of its last execution. */
    private final ConcurrentHashMap<String, Long> executed = new ConcurrentHashMap<>();
    /** Sids whose kick another plugin cancelled ({@code KordonRemoteKickEvent}). */
    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();

    public KickExecutor(Platform platform, SessionTracker sessions, Messages messages, ThrottledLog log,
                        Executor events, Clock clock) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.log = Objects.requireNonNull(log, "log");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Hands the new orders to the events executor and returns at once.
     *
     * @return how many orders will be executed (0 when all were repeats or suppressed); the sync loop uses this to
     *         poll again soon so the resulting quit events reach the panel quickly
     */
    public int submit(List<Kick> kicks) {
        if (kicks == null || kicks.isEmpty()) return 0;
        long now = clock.nanos();
        prune(now);
        List<Kick> fresh = new ArrayList<>();
        for (Kick kick : kicks) {
            if (kick == null || kick.sid() == null) continue;
            String sid = kick.sid().toLowerCase(Locale.ROOT);
            if (suppressed.contains(sid)) continue;
            boolean[] isNew = {false};
            executed.compute(sid, (k, last) -> {
                if (last != null && now - last < DEDUP.toNanos()) return last;
                isNew[0] = true;
                return now;
            });
            if (isNew[0]) fresh.add(new Kick(sid, kick.message(), kick.reason()));
        }
        if (fresh.isEmpty()) return 0;
        try {
            events.execute(() -> {
                for (Kick kick : fresh) execute(kick);
            });
        } catch (RejectedExecutionException stopping) {
            return 0; // the events executor is shut down: the core is stopping
        }
        return fresh.size();
    }

    /** Runs one order on the events executor. Never throws. */
    private void execute(Kick kick) {
        String sid = kick.sid();
        try {
            Optional<OnlinePlayer> tracked = sessions.playerOf(sid);
            if (tracked.isEmpty()) {
                // Not (or no longer) on this proxy: let the panel close the session.
                if (sessions.reportGone(sid)) log.debug(() -> "Kordon: kick for an unknown session " + sid + " → quit(gone)");
                return;
            }
            OnlinePlayer player = tracked.get();
            KickCause cause = KickCause.fromWire(kick.reason());
            String message = kick.message() == null || kick.message().isBlank() ? DEFAULT_MESSAGE : kick.message();
            String text = Legacy.translate(message);
            String shown;
            try {
                shown = platform.fireRemoteKickMessage(player, cause, text);
            } catch (RuntimeException e) {
                // A broken handler of the public event never stops the panel's kick.
                log.warn("kick.event", "Kordon: a KordonRemoteKickEvent handler failed: " + e);
                shown = text;
            }
            if (shown == null) {
                suppressed.add(sid);
                log.debug(() -> "Kordon: kick of " + player.name() + " cancelled by another plugin");
                return;
            }
            sessions.markKicked(sid);
            player.disconnect(shown);
            log.info(messages.console(MessageKey.LOG_REMOTE_KICK, "name", Messages.literal(player.name()),
                    "why", messages.english().get(why(cause))));
        } catch (RuntimeException e) {
            log.warn("kick.failed", "Kordon: kick not executed: " + e);
        }
    }

    private static MessageKey why(KickCause cause) {
        return switch (cause) {
            case PANEL -> MessageKey.KICK_WHY_PANEL;
            case BLACKLIST -> MessageKey.KICK_WHY_BLACKLIST;
            case GAME -> MessageKey.KICK_WHY_GAME;
        };
    }

    /** Forgets executions older than the dedup window and suppressions of sessions that ended. */
    private void prune(long now) {
        long window = DEDUP.toNanos();
        executed.values().removeIf(at -> now - at >= window);
        if (!suppressed.isEmpty()) suppressed.removeIf(sid -> sessions.playerOf(sid).isEmpty());
    }

    boolean isSuppressed(String sid) {
        return suppressed.contains(sid);
    }
}
