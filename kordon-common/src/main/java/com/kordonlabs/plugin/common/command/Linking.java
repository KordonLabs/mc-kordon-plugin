package com.kordonlabs.plugin.common.command;

import java.util.concurrent.CompletableFuture;

/**
 * What {@code /kordon link <key>} and {@code /kordon unlink} need from the core. Both futures complete normally with
 * an outcome, never an exception, within 30 seconds. The key never appears in a result.
 */
public interface Linking {

    /** Result of {@link #link}. */
    enum Outcome {
        /** The panel accepted the key; {@code secret.key} is written and in use. */
        LINKED,
        /** The key is already in use. */
        SAME_KEY,
        /** Not a Kordon key. */
        INVALID,
        /** The panel rejected the key (401); nothing written. */
        REFUSED,
        /** The account is disabled (403); nothing written. */
        ACCOUNT_DISABLED,
        /** Rate limited (429); nothing written. */
        RATE_LIMITED,
        /** No answer, 5xx, a redirect or an unexpected answer; nothing written. */
        UNREACHABLE,
        /** The panel accepted the key, but {@code secret.key} could not be written; nothing changed. */
        SAVE_FAILED,
        /** No Kordon API address is known (a broken build). */
        NO_API,
        /** Another link or unlink is running. */
        BUSY,
        /** Kordon is stopping or not running. */
        NOT_RUNNING
    }

    /**
     * @param serverName    LINKED: the panel's server name
     * @param tokenPrefix   the prefix of the key that was tried
     * @param apiUrl        the Kordon API address asked
     * @param cause         UNREACHABLE: a {@code cause.*} key suffix ({@code network}, {@code timeout},
     *                      {@code server_error}, {@code redirect}, {@code unsupported})
     * @param retryAfterSec RATE_LIMITED: the panel's {@code Retry-After}
     * @param error         SAVE_FAILED: what failed (an exception class), never the key
     */
    record LinkResult(Outcome outcome, String serverName, String tokenPrefix, String apiUrl, String cause,
                      int retryAfterSec, String error) {

        public static LinkResult of(Outcome outcome) {
            return new LinkResult(outcome, null, null, null, null, 0, null);
        }
    }

    /** Result of {@link #unlink}. */
    enum UnlinkOutcome { UNLINKED, NOT_LINKED, FAILED, BUSY, NOT_RUNNING }

    /**
     * @param embeddedPrefix UNLINKED: the prefix of the key built into Kordon.jar (it returns after a restart); null
     *                       when the jar carries none
     * @param error          FAILED: what failed (an exception class)
     */
    record UnlinkResult(UnlinkOutcome outcome, String embeddedPrefix, String error) {

        public static UnlinkResult of(UnlinkOutcome outcome) {
            return new UnlinkResult(outcome, null, null);
        }
    }

    /** Validates {@code key} with a heartbeat, then saves it and switches to it; {@code actor} is used in the log. */
    CompletableFuture<LinkResult> link(String key, String actor);

    /** Deletes {@code secret.key} and stops checking players. */
    CompletableFuture<UnlinkResult> unlink(String actor);
}
