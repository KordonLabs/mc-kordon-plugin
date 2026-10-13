package com.kordonlabs.plugin.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The plugin's only JSON codec. Gson is shaded and relocated in Kordon.jar, so whichever Gson version the proxy ships
 * (if any) never matters. Nothing else in the plugin imports Gson.
 *
 * <ul>
 *   <li>Records map field-for-field; {@code null} fields are omitted; unknown JSON fields are ignored.</li>
 *   <li>No HTML escaping, so color codes such as {@code &c} stay intact.</li>
 *   <li>Never throws to callers: a failure returns {@code null} and writes a debug line.</li>
 * </ul>
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapterFactory(new SyncEventAdapterFactory())
            .create();

    private static volatile Consumer<String> debug = null;

    private Json() {
    }

    public static String toJson(Object value) {
        try {
            return GSON.toJson(value);
        } catch (RuntimeException | StackOverflowError e) {
            debug(() -> "JSON: cannot serialise " + (value == null ? "null" : value.getClass().getSimpleName()) + ": " + e);
            return null;
        }
    }

    /** Null for null, blank or invalid input, or a type mismatch. */
    public static <T> T fromJson(String json, Class<T> type) {
        return fromJson(json, (Type) type);
    }

    /** Like {@link #fromJson(String, Class)} for a generic type (e.g. a {@code ParameterizedType} of a list). */
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isBlank()) return null;
        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException | StackOverflowError e) {
            debug(() -> "JSON: cannot read " + type.getTypeName() + ": " + e);
            return null;
        }
    }

    /** Where failures are reported; null to report nowhere. */
    public static void debugLog(Consumer<String> sink) {
        debug = sink;
    }

    private static void debug(Supplier<String> message) {
        Consumer<String> sink = debug;
        if (sink != null) sink.accept(message.get());
    }
}
