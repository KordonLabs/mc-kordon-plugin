package com.kordonlabs.plugin.common.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * The plugin's texts. Player-facing texts come from {@code kordon/messages_ru.properties}, read as UTF-8 rather than
 * the platform charset, which may be something else such as Cp1251 on Windows. Placeholders are named
 * ({@code {name}}) and filled from {@code get(key, "name", value, "ip", ip)}. A missing key yields the key itself, an
 * unknown placeholder stays as written and a null value renders as "". Substitution is single-pass, so a value
 * containing {@code {x}} is never expanded again. Immutable and thread-safe.
 *
 * <p>A value that players control (a nick, a typed argument) is passed as {@link #literal}: its {@code &} and
 * {@code §} are never read as colour codes and it cannot break the line. {@link #chat} and {@link #console} insert it
 * after the template's codes were applied, so staff chat and the console show the nick exactly as it is;
 * {@link #get}, which returns raw {@code &} text for later translation, makes it {@link Legacy#inert}.
 *
 * <p>The console is in English: {@link #console} renders from {@code kordon/messages_en.properties}, and
 * {@link #english} gives that view for labels put into a console line. A key the English file lacks falls back to
 * the Russian text.
 */
public final class Messages {

    public static final String RESOURCE = "kordon/messages_ru.properties";
    /** The console texts (log lines and the labels inside them). */
    public static final String RESOURCE_EN = "kordon/messages_en.properties";

    /** A placeholder value shown as it is (see the class doc). */
    public record Literal(String text) {

        @Override
        public String toString() {
            return Legacy.inert(text);
        }
    }

    /** Marks a player-controlled value (nick, typed argument, foreign text) for a placeholder; null becomes "". */
    public static Literal literal(Object value) {
        return new Literal(value == null ? "" : String.valueOf(value));
    }

    private final Map<String, String> texts;
    /** The English view for the console; itself when there is no English file, as in tests. */
    private final Messages english;

    private Messages(Map<String, String> texts, Map<String, String> englishTexts) {
        this.texts = texts;
        this.english = englishTexts == null ? this : new Messages(englishTexts, null);
    }

    /** The bundled texts. Never throws: an unreadable resource gives an empty set (every key prints as itself). */
    public static Messages load() {
        return of(read(RESOURCE), read(RESOURCE_EN));
    }

    private static Properties read(String resource) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // Corrupt resource: fall through with what was read; keys print as themselves.
        }
        return p;
    }

    /** Texts from the given properties, with the console in the same language. */
    public static Messages of(Properties properties) {
        return new Messages(toMap(properties), null);
    }

    /** Russian texts, with English console texts layered over them (a key English lacks keeps its Russian text). */
    public static Messages of(Properties russian, Properties english) {
        Map<String, String> ru = toMap(russian);
        Map<String, String> en = new TreeMap<>(ru);
        en.putAll(toMap(english));
        return new Messages(ru, Collections.unmodifiableMap(en));
    }

    private static Map<String, String> toMap(Properties properties) {
        Map<String, String> map = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) map.put(name, properties.getProperty(name));
        return Collections.unmodifiableMap(map);
    }

    /** The console's texts: use it for every label put into a {@link #console} line. */
    public Messages english() {
        return english;
    }

    /** The raw text, {@code &} codes kept, with placeholders filled. */
    public String get(MessageKey key, Object... namedArgs) {
        return get(key.key(), namedArgs);
    }

    /** The raw text of a key built at run time ({@code "reason." + code}); a missing key yields the key itself. */
    public String get(String key, Object... namedArgs) {
        String template = texts.get(key);
        return template == null ? key : format(template, namedArgs);
    }

    /** Like {@link #get(String, Object...)}, but {@code fallback} (used as is) when the key does not exist. */
    public String getOr(String key, String fallback, Object... namedArgs) {
        String template = texts.get(key);
        return template == null ? fallback : format(template, namedArgs);
    }

    /**
     * For players: placeholders filled, then {@code &} codes translated ({@link Legacy#translate}); {@link #literal}
     * values are inserted as {@link Legacy#plain} text.
     */
    public String chat(MessageKey key, Object... namedArgs) {
        return render(key.key(), namedArgs, Legacy::translate);
    }

    /**
     * For the console, in English: placeholders filled, colour codes removed ({@link Legacy#strip}); {@link #literal}
     * values are inserted as {@link Legacy#plain} text, so a nick {@code a&bc} is logged as {@code a&bc}.
     */
    public String console(MessageKey key, Object... namedArgs) {
        return english.render(key.key(), namedArgs, Legacy::strip);
    }

    public boolean has(String key) {
        return texts.containsKey(key);
    }

    /** Every key of the loaded file, sorted. */
    public Set<String> keys() {
        return texts.keySet();
    }

    /**
     * Fills {@code {name}} placeholders from alternating name/value arguments ({@code "name", "Steve", "n", 3}).
     * A trailing name without a value is ignored.
     */
    public static String format(String template, Object... namedArgs) {
        if (namedArgs == null || namedArgs.length < 2 || template.indexOf('{') < 0) return template;
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i + 1) {
                    String name = template.substring(i + 1, end);
                    int arg = indexOf(namedArgs, name);
                    if (arg >= 0) {
                        Object value = namedArgs[arg + 1];
                        out.append(value == null ? "" : value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The template with {@code codes} applied to its own text together with the ordinary values, and each
     * {@link Literal} value inserted afterwards as {@link Legacy#plain} text.
     */
    private String render(String key, Object[] namedArgs, UnaryOperator<String> codes) {
        String template = texts.get(key);
        if (template == null) return codes.apply(key);
        if (!hasLiteral(namedArgs)) return codes.apply(format(template, namedArgs));
        StringBuilder out = new StringBuilder(template.length() + 32);
        StringBuilder coded = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i + 1) {
                    int arg = indexOf(namedArgs, template.substring(i + 1, end));
                    if (arg >= 0) {
                        Object value = namedArgs[arg + 1];
                        if (value instanceof Literal literal) {
                            out.append(codes.apply(coded.toString()));
                            coded.setLength(0);
                            out.append(Legacy.plain(literal.text()));
                        } else {
                            coded.append(value == null ? "" : value);
                        }
                        i = end + 1;
                        continue;
                    }
                }
            }
            coded.append(c);
            i++;
        }
        return out.append(codes.apply(coded.toString())).toString();
    }

    private static boolean hasLiteral(Object[] namedArgs) {
        if (namedArgs == null) return false;
        for (int i = 1; i < namedArgs.length; i += 2) {
            if (namedArgs[i] instanceof Literal) return true;
        }
        return false;
    }

    private static int indexOf(Object[] namedArgs, String name) {
        for (int i = 0; i + 1 < namedArgs.length; i += 2) {
            if (name.equals(namedArgs[i])) return i;
        }
        return -1;
    }
}
