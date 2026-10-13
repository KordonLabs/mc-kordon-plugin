package com.kordonlabs.plugin.common.text;

import com.kordonlabs.plugin.common.login.LoginVerdict;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Human-readable words for a verdict's reason codes, as used in log lines and staff notifications, worded like the
 * panel's own labels.
 *
 * <p>Sub-codes fold into their main label ({@code attack:<signal>} adds a qualifier; the operator of
 * {@code mobile:<country>:<operator>} is never printed). The panel's internal codes never appear as such: a
 * Kordon-wide block names its category, an automatic one its connection type, and a stale detection reads as a
 * suspicious address. Codes from older panels map to the label the panel gives them. Any unknown code or
 * {@code decidedBy} reads as "other reason", never the raw code.
 *
 * <p>The public events still carry the raw codes.
 */
public final class ReasonLabels {

    private static final Set<String> TYPES = Set.of("vpn", "proxy", "resproxy", "tor", "hosting");
    private static final Set<String> BASE_CATEGORIES = Set.of("bot", "fraud", "cheat", "abuse");
    /** Codes with a {@code reason.<code>} label of their own. */
    private static final Set<String> KNOWN = Set.of("vpn", "proxy", "resproxy", "tor", "hosting", "suspicious", "country",
            "blacklist", "whitelist", "check_failed", "private_ip", "mobile", "nick", "attack", "repeat_bot",
            "maintenance", "plan_limit");
    /** {@code attack:<signal>} values with a qualifier of their own. */
    private static final Set<String> ATTACK_SIGNALS = Set.of("brandsame", "brandmissing", "gibberish", "digits",
            "prefixcounter", "nearduplicate", "sameshape", "protocol");
    /** Codes of older panels, mapped to the code whose label they share, as the panel does. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("budget", "check_failed"),
            Map.entry("compromised", "proxy"),
            Map.entry("iphub:block1", "hosting"),
            Map.entry("challenge", "suspicious"),
            Map.entry("scraper", "suspicious"),
            Map.entry("risk", "suspicious"),
            Map.entry("iphub:block2", "suspicious"),
            Map.entry("provider_error", "check_failed"),
            Map.entry("unavailable", "check_failed"),
            Map.entry("error", "check_failed"),
            Map.entry("secondary_unavailable", "check_failed"),
            Map.entry("ip:denylist", "blacklist"),
            Map.entry("ip:allowlist", "whitelist"));
    private static final String RISK_PREFIX = "risk>=";
    private static final String COUNTRY_PREFIX = "country:";

    private static final String BASE = "kordon_base";
    private static final String BASE_AUTO = "kordon_base_auto";
    private static final String STALE = "previously_detected";
    private static final String BASE_EXCEPTION = "base_exception";
    private static final String ATTACK = "attack";
    private static final String NICK = "nick";
    private static final String NICK_KORDON = "nick:kordon";
    private static final String MOBILE = "mobile";

    private ReasonLabels() {
    }

    /** A comma-separated list of labels such as "VPN, hosting"; "—" when there is nothing to name. */
    public static String render(LoginVerdict v, Messages m) {
        List<String> codes = new ArrayList<>();
        for (String raw : v.reasons()) {
            if (raw == null) continue;
            String code = raw.strip().toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) codes.add(alias(code));
        }
        boolean hasType = false;
        boolean hasStale = false;
        boolean hasAuto = false;
        boolean hasBaseCategory = false;
        boolean hasNickKordon = false;
        for (String code : codes) {
            hasType |= TYPES.contains(code);
            hasStale |= is(code, STALE);
            hasAuto |= is(code, BASE_AUTO);
            hasBaseCategory |= code.startsWith(BASE + ":");
            hasNickKordon |= code.equals(NICK_KORDON);
        }

        Set<String> labels = new LinkedHashSet<>();
        for (String code : codes) {
            if (code.startsWith(BASE + ":")) {
                String category = code.substring(BASE.length() + 1);
                if (TYPES.contains(category)) labels.add(reason(m, category));
                else if (BASE_CATEGORIES.contains(category)) labels.add(m.get("reason.kordon_base." + category));
                else labels.add(m.get(MessageKey.REASON_KORDON_BASE));
            } else if (code.equals(BASE)) {
                if (!hasBaseCategory) labels.add(m.get(MessageKey.REASON_KORDON_BASE));
            } else if (code.startsWith(BASE_AUTO + ":")) {
                String type = code.substring(BASE_AUTO.length() + 1);
                if (!hasType && TYPES.contains(type)) labels.add(reason(m, type));
            } else if (code.startsWith(BASE) || is(code, STALE) || is(code, BASE_EXCEPTION)) {
                // kordon_base_auto, kordon_base_clean, stale detections, exceptions: never shown as codes
            } else if (is(code, ATTACK)) {
                labels.add(attackLabel(m, codes));
            } else if (is(code, NICK)) {
                labels.add(m.get(hasNickKordon ? MessageKey.REASON_NICK_KORDON : MessageKey.REASON_NICK));
            } else if (is(code, MOBILE)) {
                labels.add(m.get(MessageKey.REASON_MOBILE));
            } else if (KNOWN.contains(code)) {
                labels.add(reason(m, code));
            } else {
                labels.add(m.get(MessageKey.REASON_OTHER));
            }
        }

        // A stale detection only hints at its old types (the panel shows them in a tooltip): no type labels.
        if (hasStale && !hasAuto) {
            for (String type : TYPES) labels.remove(reason(m, type));
        }
        if (labels.isEmpty() && hasStale) labels.add(m.get(MessageKey.REASON_SUSPICIOUS));
        String decidedBy = v.decidedBy() == null ? null : v.decidedBy().strip().toLowerCase(Locale.ROOT);
        if (labels.isEmpty() && decidedBy != null && !decidedBy.isEmpty() && !"clean".equals(decidedBy)) {
            labels.add(decidedByLabel(m, decidedBy));
        }
        return labels.isEmpty() ? "—" : String.join(", ", labels);
    }

    /** The label of a verdict without reason codes, from what decided it; an unknown value reads as "other reason". */
    private static String decidedByLabel(Messages m, String decidedBy) {
        if (decidedBy.startsWith(BASE)) return m.get(MessageKey.REASON_KORDON_BASE);
        if (decidedBy.startsWith(STALE)) return m.get(MessageKey.REASON_SUSPICIOUS);
        if (decidedBy.equals("antivpn") || decidedBy.equals("monitor")) return m.get(MessageKey.REASON_SUSPICIOUS);
        if (decidedBy.equals("error")) return m.get(MessageKey.REASON_CHECK_FAILED);
        if (decidedBy.equals(ATTACK)) return attackLabel(m, List.of());
        if (KNOWN.contains(decidedBy)) return reason(m, decidedBy);
        return m.get(MessageKey.REASON_OTHER);
    }

    /** The bots label, with the known {@code attack:<signal>} qualifiers in parentheses in their order. */
    private static String attackLabel(Messages m, List<String> codes) {
        Set<String> details = new LinkedHashSet<>();
        for (String code : codes) {
            if (!code.startsWith(ATTACK + ":")) continue;
            String signal = code.substring(ATTACK.length() + 1);
            if (ATTACK_SIGNALS.contains(signal)) details.add(m.get("reason.attack." + signal));
        }
        String bots = m.get(MessageKey.REASON_ATTACK);
        return details.isEmpty() ? bots : bots + " (" + String.join(", ", details) + ")";
    }

    /** Maps an older panel's code to the code whose label it shares. */
    private static String alias(String code) {
        String to = ALIASES.get(code);
        if (to != null) return to;
        if (code.startsWith(RISK_PREFIX)) return "suspicious";
        if (code.startsWith(COUNTRY_PREFIX)) return "country";
        return code;
    }

    /** {@code code} is {@code family} or one of its {@code family:<sub>} codes. */
    private static boolean is(String code, String family) {
        return code.equals(family) || code.startsWith(family + ":");
    }

    /** The {@code reason.<code>} label of a code in {@link #KNOWN} or a connection type. */
    private static String reason(Messages m, String code) {
        return m.get("reason." + code);
    }
}
