package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * 200 answer of {@code POST /login} (always exactly these fields).
 *
 * @param allow      false = kick with {@code message}; null only in a malformed answer ({@link #wellFormed()})
 * @param result     "allowed" | "blocked"
 * @param decidedBy  "antivpn" | "clean" | "error" | "blacklist" | "whitelist" | "country"
 * @param reasons    reason codes ({@code reason.*} labels); never null after parsing
 * @param message    the kick text ({@code &}-codes) when {@code allow} is false, else null
 * @param wouldBlock monitor mode: allowed, but protection would have blocked
 * @param loginId    the journal row of this attempt (sent back in the sync {@code join}); nullable
 * @param notice     the staff chat line ({@code &}-codes, placeholders filled) for a blocked or allowed login; null
 *                   when that notice is off in the panel or the panel is older
 * @param notices    {@code notice} in other languages, an object mapping lower-case ISO 639-1 codes to lines. Read as
 *                   any JSON value so that a malformed one never spoils the answer; null when absent
 */
public record LoginResponse(Boolean allow, String result, String decidedBy, List<String> reasons, String message,
                            boolean wouldBlock, Long loginId, String notice, Object notices) {

    public LoginResponse {
        reasons = reasons == null ? List.of() : reasons;
    }

    /** Without notices in other languages. */
    public LoginResponse(Boolean allow, String result, String decidedBy, List<String> reasons, String message,
                         boolean wouldBlock, Long loginId, String notice) {
        this(allow, result, decidedBy, reasons, message, wouldBlock, loginId, notice, null);
    }

    /** Without a staff notice. */
    public LoginResponse(Boolean allow, String result, String decidedBy, List<String> reasons, String message,
                         boolean wouldBlock, Long loginId) {
        this(allow, result, decidedBy, reasons, message, wouldBlock, loginId, null, null);
    }

    /** A 200 body the plugin can act on; otherwise the call is treated as UNSUPPORTED and failOpen applies. */
    public boolean wellFormed() {
        return allow != null;
    }
}
