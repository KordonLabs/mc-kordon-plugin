package com.kordonlabs.plugin.common.command;

import com.kordonlabs.plugin.common.api.dto.ListsRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Argument parsing for {@code /kordon}. Pure parsing, no I/O.
 *
 * <p>The list value is a nick, UUID, IP or CIDR range; {@code --ip} / {@code --name} anywhere after the operation force
 * the kind (the last one wins). For {@code add}, an optional duration ({@link Expiry}) may follow the value and the
 * remaining tokens form the note.
 */
public final class ArgParser {

    public enum Sub { HELP, STATUS, RELOAD, LOOKUP, WHITELIST, BLACKLIST, LINK, UNLINK }

    /** The panel's note length limit. */
    static final int MAX_NOTE = 200;
    /** The panel's value length limit. */
    static final int MAX_VALUE = 64;
    static final String FLAG_IP = "--ip";
    static final String FLAG_NAME = "--name";
    static final String KIND_AUTO = "auto";
    static final String KIND_IP = "ip";
    static final String KIND_NAME = "name";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("\\[?[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[0-9A-Za-z._-]+)?]?");
    private static final Pattern PREFIX = Pattern.compile("\\d{1,3}");

    /**
     * A parsed {@code whitelist|blacklist} command.
     *
     * @param list          {@code ListsRequest.WHITELIST} / {@code BLACKLIST}
     * @param op            {@code ListsRequest.ADD} / {@code REMOVE}
     * @param kind          "auto", "ip" or "name"
     * @param expiry        add only: a panel expiry code, or null for no expiry
     * @param note          add only: trimmed, at most 200 chars; null when empty
     * @param noteTruncated the note was longer and has been cut
     */
    public record ListArgs(String list, String op, String value, String kind, String expiry, String note,
                           boolean noteTruncated) {
    }

    private ArgParser() {
    }

    /** The sub-command of {@code token}; null when unknown. */
    public static Sub sub(String token) {
        if (token == null) return null;
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "help", "?" -> Sub.HELP;
            case "status" -> Sub.STATUS;
            case "reload" -> Sub.RELOAD;
            case "lookup" -> Sub.LOOKUP;
            case "whitelist", "wl", "белый" -> Sub.WHITELIST;
            case "blacklist", "bl", "чёрный", "черный" -> Sub.BLACKLIST;
            case "link" -> Sub.LINK;
            case "unlink" -> Sub.UNLINK;
            default -> null;
        };
    }

    /**
     * Strips blanks and one surrounding pair of quotes or angle brackets from a key typed after
     * {@code /kordon link}, in case it was pasted together with the brackets from the instructions.
     */
    static String cleanKey(String typed) {
        if (typed == null) return null;
        String s = typed.strip();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'') || (first == '<' && last == '>')) {
                s = s.substring(1, s.length() - 1).strip();
            }
        }
        return s;
    }

    /** {@code ListsRequest.ADD} / {@code REMOVE} for {@code token}; null when unknown. */
    public static String op(String token) {
        if (token == null) return null;
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "add" -> ListsRequest.ADD;
            case "remove", "rm", "del" -> ListsRequest.REMOVE;
            default -> null;
        };
    }

    /**
     * Parses {@code args[from]} as the operation, followed by the value, duration, note and flags. Returns null when
     * there is not enough to act on.
     */
    public static ListArgs parseList(String list, String[] args, int from) {
        if (args == null || args.length <= from) return null;
        String op = op(args[from]);
        if (op == null) return null;
        String kind = KIND_AUTO;
        List<String> rest = new ArrayList<>();
        for (int i = from + 1; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) continue;
            if (token.equalsIgnoreCase(FLAG_IP)) kind = KIND_IP;
            else if (token.equalsIgnoreCase(FLAG_NAME)) kind = KIND_NAME;
            else rest.add(trimTyped(token));
        }
        if (rest.isEmpty()) return null;
        String value = rest.get(0);
        if (value.length() > MAX_VALUE) return null;
        if (op.equals(ListsRequest.REMOVE)) return new ListArgs(list, op, value, kind, null, null, false);

        int noteFrom = 1;
        String expiry = null;
        if (rest.size() > 1) {
            expiry = Expiry.parse(rest.get(1));
            if (expiry != null) noteFrom = 2;
        }
        String note = String.join(" ", rest.subList(noteFrom, rest.size())).strip();
        boolean truncated = note.length() > MAX_NOTE;
        if (truncated) {
            int end = Character.isHighSurrogate(note.charAt(MAX_NOTE - 1)) ? MAX_NOTE - 1 : MAX_NOTE;
            note = note.substring(0, end).strip();
        }
        return new ListArgs(list, op, value, kind, expiry, note.isEmpty() ? null : note, truncated);
    }

    /**
     * Trims only ASCII blanks (space, tab, line breaks), matching the panel. Not {@link String#strip()}: that also
     * removes U+3000 and U+2000 to U+200A, which can be part of an offline nick, so "Steve" with a trailing U+3000
     * would silently become a different account. The panel must see such a value as typed and refuse it.
     */
    static String trimTyped(String value) {
        int from = 0;
        int to = value.length();
        while (from < to && typedBlank(value.charAt(from))) from++;
        while (to > from && typedBlank(value.charAt(to - 1))) to--;
        return value.substring(from, to);
    }

    private static boolean typedBlank(char c) {
        return c == ' ' || (c >= 0x09 && c <= 0x0D); // tab, LF, VT, FF, CR
    }

    /** An IPv4 or IPv6 address (shape only; the panel validates it). */
    public static boolean isIp(String value) {
        if (value == null || value.isEmpty()) return false;
        return IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
    }

    /** An address or a range {@code addr/prefix}. */
    public static boolean isIpOrCidr(String value) {
        if (value == null) return false;
        int slash = value.indexOf('/');
        if (slash < 0) return isIp(value);
        return isIp(value.substring(0, slash)) && PREFIX.matcher(value.substring(slash + 1)).matches();
    }

    /**
     * Whether the address belongs to a local network and can never be a player's real address: unspecified,
     * loopback, private (RFC 1918), link-local, CGNAT (100.64/10) or unique-local (fc00::/7). Mirrors the panel,
     * which refuses list entries for such addresses. False for anything that is not an address literal; never does
     * a DNS lookup.
     */
    public static boolean isLocalNetworkIp(String ip) {
        InetAddress address = literal(ip);
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xFF;
            int a1 = b[1] & 0xFF;
            return address.isSiteLocalAddress() || (a0 == 100 && a1 >= 64 && a1 <= 127);
        }
        return (b[0] & 0xFE) == 0xFC; // IPv6 unique-local (Java's isSiteLocalAddress is the deprecated fec0::/10)
    }

    /** The address of an IP literal ({@code [..]} and {@code %scope} allowed); null otherwise. Never resolves a name. */
    private static InetAddress literal(String ip) {
        if (!isIp(ip)) return null;
        String s = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
        int scope = s.indexOf('%');
        if (scope >= 0) s = s.substring(0, scope);
        try {
            if (IPV4.matcher(s).matches()) {
                String[] parts = s.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet > 255) return null;
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            // With a ':' and a hex digit or ':' first, getByName parses an IPv6 literal (or fails) and never looks up.
            if (s.isEmpty() || s.indexOf(':') < 0 || (Character.digit(s.charAt(0), 16) < 0 && s.charAt(0) != ':')) {
                return null;
            }
            return InetAddress.getByName(s);
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}
