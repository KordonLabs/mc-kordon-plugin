package com.kordonlabs.plugin.common.text;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A staff chat line in the server's main language plus the same line in other languages. Each staff member reads the
 * line in their client's language; everyone else reads {@code fallback}.
 *
 * @param fallback   the line for everyone without one of their own; never null
 * @param byLanguage lines keyed by lower-case ISO 639-1 code; never null, at most {@value #MAX_LANGUAGES} entries
 */
public record LocalizedLine(String fallback, Map<String, String> byLanguage) {

    /** The most languages read from one answer; the rest are ignored. */
    public static final int MAX_LANGUAGES = 32;

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");

    public LocalizedLine {
        if (fallback == null) throw new IllegalArgumentException("fallback");
        byLanguage = byLanguage == null ? Map.of() : Map.copyOf(byLanguage);
    }

    /** One line for everyone. */
    public static LocalizedLine of(String line) {
        return new LocalizedLine(line, Map.of());
    }

    /**
     * Reads the panel's {@code notices} from parsed JSON: only non-blank string values under a two-letter lower-case
     * key, at most {@value #MAX_LANGUAGES}. Anything else yields an empty map.
     */
    public static Map<String, String> languages(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (out.size() >= MAX_LANGUAGES) break;
            if (e.getKey() instanceof String key && LANGUAGE.matcher(key).matches()
                    && e.getValue() instanceof String line && !line.isBlank()) {
                out.put(key, line);
            }
        }
        return Map.copyOf(out);
    }

    /** The language of a client locale such as {@code en-US} or {@code en_us}: its two-letter code, else null. */
    public static String language(String locale) {
        if (locale == null || locale.length() < 2) return null;
        String lang = locale.substring(0, 2).toLowerCase(Locale.ROOT);
        if (locale.length() > 2 && Character.isLetter(locale.charAt(2))) return null; // "fil", "und": not ISO 639-1
        return LANGUAGE.matcher(lang).matches() ? lang : null;
    }

    /** The line for a client with {@code locale} (BCP 47, nullable): its language's line, or {@code fallback}. */
    public String forLocale(String locale) {
        String lang = language(locale);
        String line = lang == null ? null : byLanguage.get(lang);
        return line == null ? fallback : line;
    }

    /** True when some language has a line of its own. */
    public boolean localized() {
        return !byLanguage.isEmpty();
    }

    /** Every line passed through {@code f}, such as {@link Legacy#translate}. */
    public LocalizedLine map(UnaryOperator<String> f) {
        Map<String, String> out = new LinkedHashMap<>();
        byLanguage.forEach((lang, line) -> out.put(lang, f.apply(line)));
        return new LocalizedLine(f.apply(fallback), out);
    }
}
