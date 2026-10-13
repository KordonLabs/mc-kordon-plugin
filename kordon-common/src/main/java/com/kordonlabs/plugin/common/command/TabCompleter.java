package com.kordonlabs.plugin.common.command;

import com.kordonlabs.plugin.common.api.dto.ListsRequest;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Permissions;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.platform.Sender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code /kordon} tab completion, without I/O. Offers only the sub-commands the sender may use, and only online player
 * names that reach the panel unchanged ({@link #listable}). {@code link} and {@code unlink} complete nothing, so a key
 * is never suggested.
 */
public final class TabCompleter {

    static final int MAX_NAMES = 50;
    /** The longest nick, in UTF-16 units as the panel counts. */
    static final int MAX_NAME = 16;
    private static final List<String> OPS = List.of("add", "remove");
    private static final List<String> FLAGS = List.of(ArgParser.FLAG_IP, ArgParser.FLAG_NAME);

    private final Platform platform;

    public TabCompleter(Platform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public List<String> complete(Sender sender, String[] args) {
        String[] a = args == null || args.length == 0 ? new String[] {""} : args;
        String current = a[a.length - 1] == null ? "" : a[a.length - 1];
        if (a.length == 1) return subCommands(sender, current);
        ArgParser.Sub sub = ArgParser.sub(a[0]);
        if (sub == null) return List.of();
        return switch (sub) {
            case LOOKUP -> a.length == 2 && sender.hasPermission(Permissions.LOOKUP) ? names(current) : List.of();
            case WHITELIST -> sender.hasPermission(Permissions.WHITELIST) ? list(a, current) : List.of();
            case BLACKLIST -> sender.hasPermission(Permissions.BLACKLIST) ? list(a, current) : List.of();
            case LINK, UNLINK -> List.of();
            default -> List.of();
        };
    }

    private List<String> subCommands(Sender sender, String prefix) {
        List<String> out = new ArrayList<>();
        if (sender.hasPermission(Permissions.COMMAND)) {
            out.add("help");
            out.add("status");
        }
        if (sender.hasPermission(Permissions.RELOAD)) out.add("reload");
        if (sender.hasPermission(Permissions.LOOKUP)) out.add("lookup");
        if (sender.hasPermission(Permissions.WHITELIST)) out.add("whitelist");
        if (sender.hasPermission(Permissions.BLACKLIST)) out.add("blacklist");
        if (sender.hasPermission(Permissions.LINK)) {
            out.add("link");
            out.add("unlink");
        }
        return filter(out, prefix);
    }

    /** {@code a[0]} is the list and {@code a[1]} the operation, followed by value, duration, note and flags. */
    private List<String> list(String[] a, String current) {
        if (a.length == 2) return filter(OPS, current);
        String op = ArgParser.op(a[1]);
        if (op == null) return List.of();
        List<String> flags = new ArrayList<>(FLAGS);
        int values = 0;
        for (int i = 2; i < a.length - 1; i++) {
            String token = a[i] == null ? "" : a[i];
            if (FLAGS.contains(token.toLowerCase(Locale.ROOT))) flags.remove(token.toLowerCase(Locale.ROOT));
            else if (!token.isBlank()) values++;
        }
        if (current.startsWith("-")) return filter(flags, current);
        if (values == 0) return names(current);
        if (values == 1 && op.equals(ListsRequest.ADD)) {
            List<String> out = new ArrayList<>(Expiry.CODES);
            out.addAll(flags);
            return filter(out, current);
        }
        return filter(flags, current);
    }

    private List<String> names(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Collection<OnlinePlayer> online = platform.onlinePlayers();
        if (online == null) return out;
        for (OnlinePlayer player : online) {
            String name = player == null ? null : player.name();
            if (name != null && listable(name) && name.toLowerCase(Locale.ROOT).startsWith(p)) out.add(name);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > MAX_NAMES ? List.copyOf(out.subList(0, MAX_NAMES)) : out;
    }

    /**
     * Whether the name reaches the panel unchanged, matching the panel's own check: 1 to 16 UTF-16 units, no control
     * character, space, DEL, section sign, U+FFFD or U+2423, not wrapped in guillemets, and no edge blank that the
     * panel trims. Velocity admits nicks such as "Steve" with a trailing space or NBSP; offered
     * here they would arrive trimmed and the list entry or lookup would hit the real "Steve". Such a player has to be
     * listed by UUID or IP.
     */
    static boolean listable(String name) {
        int n = name.length();
        if (n < 1 || n > MAX_NAME) return false;
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == 0xA7 || c == 0xFFFD || c == 0x2423) return false; // 0xA7: section sign
        }
        if (name.charAt(0) == 0xAB && name.charAt(n - 1) == 0xBB) return false; // how the panel displays a quoted name
        return !jsTrimmed(name.charAt(0)) && !jsTrimmed(name.charAt(n - 1));
    }

    /** Above U+0020, the edge blanks the panel trims: Zs, U+2028, U+2029 and U+FEFF. */
    private static boolean jsTrimmed(char c) {
        return c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F
                || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}
