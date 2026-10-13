package com.kordonlabs.plugin.common.text;

/**
 * Minecraft legacy colour codes. The panel and the bundled messages use {@code &} codes; players get {@code §} codes
 * ({@link #translate}) and the console gets plain text ({@link #strip}). {@code &#rrggbb} becomes BungeeCord's
 * {@code §x§r§r§g§g§b§b} hex format, which Velocity's serializer also parses.
 *
 * <p>Text that players or panel users control (a nick, a client brand, a note) is never translated: it is inserted as
 * {@link #plain} text after the surrounding line was translated, or made {@link #inert} when it has to sit inside an
 * {@code &}-coded line.
 */
public final class Legacy {

    public static final char SECTION = '§';
    /** FULLWIDTH AMPERSAND, which {@link #inert} writes instead of an {@code &} that would start a code. */
    public static final char INERT_AMPERSAND = '＆';

    private Legacy() {
    }

    /**
     * Foreign text as one plain line: control characters, line breaks included, become spaces and {@code §} is
     * removed, so it can neither start a chat line of its own nor carry formatting. {@code &} is kept because nothing
     * translates it afterwards. Null becomes "".
     */
    public static String plain(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION) continue;
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }

    /**
     * {@link #plain} text that stays as it is inside an {@code &}-coded line: an {@code &} that would start a code
     * becomes {@link #INERT_AMPERSAND}, which {@link #translate} and {@link #strip} leave alone.
     */
    public static String inert(String text) {
        String s = plain(text);
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean code = c == '&' && i + 1 < s.length() && (isColourCode(s.charAt(i + 1)) || s.charAt(i + 1) == '#');
            out.append(code ? INERT_AMPERSAND : c);
        }
        return out.toString();
    }

    /**
     * Removes only {@code §} codes, including the hex form; {@code &} stays. For console lines that are already plain,
     * where an {@code &} is part of a nick rather than a code.
     */
    public static String stripSections(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf(SECTION) < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) {
                char code = text.charAt(i + 1);
                if (isColourCode(code) || code == 'x' || code == 'X') {
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Translates {@code &} codes to {@code §} codes and normalises line breaks to {@code \n}. Null becomes "". */
    public static String translate(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = newlines(text);
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char code = s.charAt(i + 1);
                if (isColourCode(code)) {
                    out.append(SECTION).append(Character.toLowerCase(code));
                    i++;
                    continue;
                }
                if (code == '#' && isHex(s, i + 2, 6)) {
                    out.append(SECTION).append('x');
                    for (int j = i + 2; j < i + 8; j++) out.append(SECTION).append(Character.toLowerCase(s.charAt(j)));
                    i += 7;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Plain text for the console: removes {@code &} and {@code §} codes, including hex colours. Null becomes "". */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = newlines(text);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == SECTION) && i + 1 < s.length()) {
                char code = s.charAt(i + 1);
                if (isColourCode(code) || (c == SECTION && (code == 'x' || code == 'X'))) {
                    i++;
                    continue;
                }
                if (c == '&' && code == '#' && isHex(s, i + 2, 6)) {
                    i += 7;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String newlines(String s) {
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean isColourCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O') || c == 'r' || c == 'R';
    }

    private static boolean isHex(String s, int from, int count) {
        if (from + count > s.length()) return false;
        for (int i = from; i < from + count; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }
}
