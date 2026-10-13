package com.kordonlabs.plugin.common.login;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The decision about one login. Produced by {@link LoginGate}, possibly overridden by another plugin through the
 * platform's public {@code KordonLoginCheckedEvent}, and applied exactly once by {@link LoginHold}.
 *
 * @param kickMessage kick screen with {@code §} codes when {@code allow} is false, else null
 * @param source      how the verdict was reached
 * @param result      the panel's "allowed" or "blocked"; null when the panel did not decide
 * @param decidedBy   the panel's {@code decidedBy}; nullable
 * @param reasons     the panel's reason codes, or a local cause; never null
 * @param wouldBlock  monitor mode: allowed, but protection would have blocked
 * @param loginId     the panel's journal row; null when the panel did not decide
 * @param notice      the staff chat line with {@code &} codes and placeholders filled. For {@code PANEL}, null means
 *                    the notice is off. For {@code FAIL_CLOSED}, the panel's "blocked" line from the heartbeat: null
 *                    when unknown (the built-in text is used) and empty when off. Null for other sources.
 * @param notices     {@code PANEL} only: {@code notice} in other languages, keyed by lower-case ISO 639-1 code, for
 *                    staff whose client uses that language; never null
 */
public record LoginVerdict(boolean allow, String kickMessage, Source source, String result, String decidedBy,
                           List<String> reasons, boolean wouldBlock, Long loginId, String notice,
                           Map<String, String> notices) {

    public LoginVerdict {
        reasons = reasons == null ? List.of() : reasons;
        notices = notices == null ? Map.of() : Map.copyOf(notices);
    }

    /** Without the staff notice in other languages. */
    public LoginVerdict(boolean allow, String kickMessage, Source source, String result, String decidedBy,
                        List<String> reasons, boolean wouldBlock, Long loginId, String notice) {
        this(allow, kickMessage, source, result, decidedBy, reasons, wouldBlock, loginId, notice, null);
    }

    /** Without a staff notice. */
    public LoginVerdict(boolean allow, String kickMessage, Source source, String result, String decidedBy,
                        List<String> reasons, boolean wouldBlock, Long loginId) {
        this(allow, kickMessage, source, result, decidedBy, reasons, wouldBlock, loginId, null);
    }

    /**
     * What the platform's public {@code KordonLoginCheckedEvent} made of {@code verdict}; only a block can change. A
     * cancelled event allows the login with source {@code OVERRIDDEN}, keeping the panel's result, reasons and loginId
     * for the journal and the log. A different kick text yields the same block with that text.
     */
    public static LoginVerdict afterPublicEvent(LoginVerdict verdict, boolean cancelled, String kickMessage) {
        if (verdict.allow()) return verdict;
        if (cancelled) {
            return new LoginVerdict(true, null, Source.OVERRIDDEN, verdict.result(), verdict.decidedBy(),
                    verdict.reasons(), verdict.wouldBlock(), verdict.loginId());
        }
        if (kickMessage == null || Objects.equals(kickMessage, verdict.kickMessage())) return verdict;
        return new LoginVerdict(false, kickMessage, verdict.source(), verdict.result(), verdict.decidedBy(),
                verdict.reasons(), verdict.wouldBlock(), verdict.loginId(), verdict.notice(), verdict.notices());
    }

    public enum Source {
        /** The panel answered {@code /login}. */
        PANEL,
        /** A recent allowed verdict for the same player and IP, reused without a request (see {@link RecentAllows}). */
        REUSED,
        /** No usable answer and failOpen is set: allowed. */
        FAIL_OPEN,
        /** No usable answer and failOpen is not set: kicked with the "check failed" text. */
        FAIL_CLOSED,
        /** The account is disabled: everyone is allowed. */
        ACCOUNT_DISABLED,
        /** API address or key missing: allowed without a check. */
        NOT_CONFIGURED,
        /** Another plugin changed the verdict in the public event. */
        OVERRIDDEN
    }
}
