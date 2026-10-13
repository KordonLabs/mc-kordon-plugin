package com.kordonlabs.plugin.common.api;

/**
 * The classified answer of one API call.
 *
 * @param outcome        what happened (see {@link Outcome})
 * @param body           the parsed 200 body; null for every other outcome
 * @param status         the HTTP status; 0 when there was no HTTP answer (network, timeout, not configured)
 * @param errorCode      {@code error.code} of the panel's error envelope, when there was one
 * @param errorMessage   {@code error.message}; for {@code 422 invalid_value} it is shown to the player verbatim
 * @param retryAfterSec  {@code retry-after} of a 429, else 0
 * @param retried        the call was repeated once after a stale-connection failure
 * @param redirectOrigin origin ({@code scheme://host[:port]}) of a 3xx {@code Location}; the redirect is never followed
 */
public record ApiResult<T>(Outcome outcome, T body, int status, String errorCode, String errorMessage,
                           int retryAfterSec, boolean retried, String redirectOrigin) {

    public enum Outcome {
        /** 200 with a parsable body. */
        OK,
        /** 401: the token is unknown (for example rotated) or malformed. */
        UNAUTHORIZED,
        /** 403 {@code account_disabled}. */
        ACCOUNT_DISABLED,
        /** 403 {@code commands_disabled} (lists/lookup): in-game commands are turned off in the panel. */
        COMMANDS_DISABLED,
        /** 429. */
        RATE_LIMITED,
        /** 422 {@code invalid_value} (lists). */
        INVALID_VALUE,
        /** 400. */
        BAD_REQUEST,
        /** 413. */
        TOO_LARGE,
        /** 404 or a non-JSON or unparsable answer (an older panel, a redeploy, a wrong panel address). */
        UNSUPPORTED,
        /** 3xx: never followed, so the token cannot travel to another host. */
        REDIRECT,
        /** 5xx or any other unexpected status. */
        SERVER_ERROR,
        /** No HTTP answer: connection refused/reset, DNS, TLS. */
        NETWORK,
        /** No answer within the call's timeout. */
        TIMEOUT,
        /** No panel URL or token: the call was not made. */
        NOT_CONFIGURED
    }

    public boolean ok() {
        return outcome == Outcome.OK;
    }

    public static <T> ApiResult<T> ok(T body, int status) {
        return new ApiResult<>(Outcome.OK, body, status, null, null, 0, false, null);
    }

    /** A failed call with an HTTP status (and, if the panel sent one, its error envelope). */
    public static <T> ApiResult<T> failure(Outcome outcome, int status, String errorCode, String errorMessage) {
        return new ApiResult<>(outcome, null, status, errorCode, errorMessage, 0, false, null);
    }

    /** A call that got no HTTP answer ({@link Outcome#NETWORK}, {@link Outcome#TIMEOUT}, {@link Outcome#NOT_CONFIGURED}). */
    public static <T> ApiResult<T> noAnswer(Outcome outcome) {
        return new ApiResult<>(outcome, null, 0, null, null, 0, false, null);
    }

    public static <T> ApiResult<T> rateLimited(int status, int retryAfterSec) {
        return new ApiResult<>(Outcome.RATE_LIMITED, null, status, "rate_limited", null, retryAfterSec, false, null);
    }

    public static <T> ApiResult<T> redirect(int status, String redirectOrigin) {
        return new ApiResult<>(Outcome.REDIRECT, null, status, null, null, 0, false, redirectOrigin);
    }

    /** The same result marked as the answer to a repeated call. */
    public ApiResult<T> withRetried() {
        return new ApiResult<>(outcome, body, status, errorCode, errorMessage, retryAfterSec, true, redirectOrigin);
    }
}
