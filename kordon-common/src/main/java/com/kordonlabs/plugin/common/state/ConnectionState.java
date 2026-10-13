package com.kordonlabs.plugin.common.state;

/** The plugin's view of its link to the panel. */
public enum ConnectionState {
    /** The last heartbeat succeeded. */
    OK,
    /** No API address or key: no network calls at all, players join unchecked. */
    NOT_CONFIGURED,
    /** The panel answered 401 (key reissued or server deleted); logins follow failOpen. */
    UNAUTHORIZED,
    /** The account is disabled: everyone is let in, with no {@code /login} calls until a heartbeat succeeds. */
    DISABLED,
    /**
     * No heartbeat answer yet, or the heartbeat got a 404, a non-JSON answer or a redirect. A heartbeat that fails
     * with a network error, a timeout, a 5xx or a 429 keeps the previous state;
     * {@link ConnectionStateHolder#lastOkAtMillis()} tells how old the last answer is.
     */
    UNREACHABLE,
    /** A successful heartbeat without {@code features}: logins work; sync, kicks, lists and lookup are off. */
    LEGACY_PANEL
}
