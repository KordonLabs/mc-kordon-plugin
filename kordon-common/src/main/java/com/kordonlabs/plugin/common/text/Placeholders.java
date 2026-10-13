package com.kordonlabs.plugin.common.text;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The panel's placeholders in kick screens and staff notices. The panel fills them itself in every {@code /login}
 * answer and kick; only the texts the plugin keeps for when Kordon does not answer come with {NICK} and {IP} left for
 * the plugin. Any other known placeholder reads {@link #UNKNOWN}.
 */
public final class Placeholders {

    public static final String UNKNOWN = "—";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_]+)\\}");
    private static final Set<String> KNOWN = Set.of("NICK", "IP", "COUNTRY", "ISP", "REASON", "ID", "SERVER");

    private Placeholders() {
    }

    /**
     * Fills {NICK} and {IP} in {@code text} with {@link Legacy#inert} values, so a nick never colours the line, and the
     * panel's other placeholders with {@link #UNKNOWN}. Unknown {WORDS} stay as written.
     */
    public static String fill(String text, String nick, String ip) {
        if (text == null || text.indexOf('{') < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        while (m.find()) {
            String key = m.group(1).toUpperCase(Locale.ROOT);
            String value;
            if (!KNOWN.contains(key)) {
                value = m.group();
            } else if (key.equals("NICK")) {
                value = orUnknown(Legacy.inert(nick));
            } else if (key.equals("IP")) {
                value = orUnknown(Legacy.inert(ip));
            } else {
                value = UNKNOWN;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.strip();
    }
}
