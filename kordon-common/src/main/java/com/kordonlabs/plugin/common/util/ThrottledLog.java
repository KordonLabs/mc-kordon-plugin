package com.kordonlabs.plugin.common.util;

import com.kordonlabs.plugin.common.install.Tokens;
import com.kordonlabs.plugin.common.text.Legacy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core's only way to the console. Only {@code §} color codes are stripped here, so an {@code &} in a player's name
 * is logged as it is. Keyed warnings are printed at most once per interval, and the next printed line of a key says
 * how many were skipped. Keys must come from a small fixed set, never from player data.
 *
 * <p>Never blocks beyond the logger itself, so it is safe on the login timer thread. As a last line of defence every
 * line is masked by {@link Tokens#maskAll}, so a key typed into {@code /kordon link} cannot reach the console even
 * through an exception message.
 */
public final class ThrottledLog {

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);
    /** Safety cap on distinct keys; past it the throttle state is forgotten and a few lines may repeat early. */
    private static final int MAX_KEYS = 1024;

    private final Logger logger;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile boolean debug;

    public ThrottledLog(Logger logger, Clock clock) {
        this.logger = logger;
        this.clock = clock;
    }

    /** Enables {@link #debug} lines. */
    public void setDebug(boolean enabled) {
        debug = enabled;
    }

    public boolean debugEnabled() {
        return debug;
    }

    public void info(String message) {
        logger.info(clean(message));
    }

    public void warn(String message) {
        logger.warning(clean(message));
    }

    public void error(String message, Throwable error) {
        logger.log(Level.SEVERE, clean(message), error);
    }

    /** A warning printed at most once per {@link #DEFAULT_INTERVAL} for {@code key}. True if printed now. */
    public boolean warn(String key, String message) {
        return warn(key, message, DEFAULT_INTERVAL);
    }

    /** A warning printed at most once per {@code interval} for {@code key}. True if printed now. */
    public boolean warn(String key, String message, Duration interval) {
        return throttled(Level.WARNING, key, message, interval);
    }

    /** An info line printed at most once per {@code interval} for {@code key}. True if printed now. */
    public boolean info(String key, String message, Duration interval) {
        return throttled(Level.INFO, key, message, interval);
    }

    /** Printed only in debug mode, at INFO level because proxies drop FINE records by default. */
    public void debug(String message) {
        if (debug) logger.info("[debug] " + clean(message));
    }

    /** Like {@link #debug(String)}, but the text is only built when debug is on. */
    public void debug(Supplier<String> message) {
        if (debug) logger.info("[debug] " + clean(message.get()));
    }

    /** Forgets the throttle state of {@code key}, so its next line is printed at once. */
    public void reset(String key) {
        windows.remove(key);
    }

    private boolean throttled(Level level, String key, String message, Duration interval) {
        long now = clock.nanos();
        long intervalNanos = interval.toNanos();
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) windows.clear();
        // [0]: print now (1) or suppress (0); [1]: lines suppressed since the previous print of this key.
        int[] decision = {0, 0};
        windows.compute(key, (k, window) -> {
            if (window == null) {
                decision[0] = 1;
                return new Window(now);
            }
            if (now - window.lastNanos >= intervalNanos) {
                decision[0] = 1;
                decision[1] = window.suppressed;
                window.lastNanos = now;
                window.suppressed = 0;
            } else {
                window.suppressed++;
            }
            return window;
        });
        if (decision[0] == 0) return false;
        String line = clean(message);
        if (decision[1] > 0) line += " (" + decision[1] + " more)";
        logger.log(level, line);
        return true;
    }

    /** Strips {@code §} color codes and cuts every key to its prefix. */
    private static String clean(String message) {
        return Tokens.maskAll(Legacy.stripSections(message));
    }

    private static final class Window {
        long lastNanos;
        int suppressed;

        Window(long lastNanos) {
            this.lastNanos = lastNanos;
        }
    }
}
