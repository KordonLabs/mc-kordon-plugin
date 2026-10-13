package com.kordonlabs.plugin.common.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a protocol number to a Minecraft release label, for console text only.
 */
public final class ProtocolVersions {

    /** Prefix of the label for a protocol number the table does not know; the number follows it. */
    public static final String UNKNOWN_PREFIX = "протокол ";

    private static final Map<Integer, String> TABLE;

    static {
        Map<Integer, String> t = new LinkedHashMap<>();
        t.put(47, "1.8.x");
        t.put(340, "1.12.2");
        t.put(754, "1.16.4–1.16.5");
        t.put(755, "1.17");
        t.put(756, "1.17.1");
        t.put(757, "1.18–1.18.1");
        t.put(758, "1.18.2");
        t.put(759, "1.19");
        t.put(760, "1.19.1–1.19.2");
        t.put(761, "1.19.3");
        t.put(762, "1.19.4");
        t.put(763, "1.20–1.20.1");
        t.put(764, "1.20.2");
        t.put(765, "1.20.3–1.20.4");
        t.put(766, "1.20.5–1.20.6");
        t.put(767, "1.21–1.21.1");
        t.put(768, "1.21.2–1.21.3");
        t.put(769, "1.21.4");
        t.put(770, "1.21.5");
        t.put(771, "1.21.6");
        t.put(772, "1.21.7–1.21.8");
        t.put(773, "1.21.9–1.21.10");
        t.put(774, "1.21.11");
        t.put(775, "26.1");
        t.put(776, "26.2");
        t.put(777, "26.3");
        TABLE = Collections.unmodifiableMap(t);
    }

    private ProtocolVersions() {
    }

    /** The whole table, ascending by protocol number; unmodifiable. */
    public static Map<Integer, String> table() {
        return TABLE;
    }

    /** The release label, such as "1.21.4", or {@link #UNKNOWN_PREFIX} plus the number when unknown. */
    public static String label(int protocol) {
        String label = TABLE.get(protocol);
        return label != null ? label : UNKNOWN_PREFIX + protocol;
    }
}
