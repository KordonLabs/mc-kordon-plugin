package com.kordonlabs.plugin.common.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The optional duration of {@code /kordon whitelist|blacklist add}: {@code 1h}, {@code 1d}, {@code 7d}, {@code 30d}
 * or {@code never}, or their Russian equivalents, case-insensitive, mapped to the panel's expiry codes. Any other
 * token is not a duration and starts the note.
 */
public final class Expiry {

    /** The panel's expiry codes, in the order tab completion offers them. */
    public static final List<String> CODES = List.of("1h", "1d", "7d", "30d", "never");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("1h", "1h"), Map.entry("1d", "1d"), Map.entry("7d", "7d"), Map.entry("30d", "30d"),
            Map.entry("never", "never"),
            Map.entry("1ч", "1h"), Map.entry("1д", "1d"), Map.entry("7д", "7d"), Map.entry("30д", "30d"),
            Map.entry("навсегда", "never"));

    private Expiry() {
    }

    /** The panel's expiry code for {@code token}; null when it is not a duration. */
    public static String parse(String token) {
        return token == null ? null : ALIASES.get(token.strip().toLowerCase(Locale.ROOT));
    }
}
