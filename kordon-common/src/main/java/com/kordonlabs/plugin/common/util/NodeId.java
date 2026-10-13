package com.kordonlabs.plugin.common.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;

/**
 * The heartbeat's {@code node}: an id for this plugin copy that needs no file. It is a name-based UUID of the machine
 * name and the canonical path of the plugin folder, so it is stable across restarts and plugin updates but differs
 * between two proxies on one machine and between machines. A changed host name (for example a recreated container
 * without a fixed hostname) yields a new node, and the panel then treats the restart as a lost connection.
 * Never throws.
 */
public final class NodeId {

    private NodeId() {
    }

    public static String derive(String host, Path dataFolder) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        String folder = dataFolder == null ? "" : canonicalFolder(dataFolder);
        return UUID.nameUUIDFromBytes(("kordon-node\n" + h + "\n" + folder).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The deepest existing ancestor resolved with {@code toRealPath()}, plus the names below it as given: the folder
     * may not exist yet, and the id must not change once it is created. Lower-cased on Windows, whose file names ignore
     * case. Falls back to the normalised absolute path when the file system cannot be asked.
     */
    static String canonicalFolder(Path dataFolder) {
        Path absolute;
        try {
            absolute = dataFolder.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            absolute = dataFolder.normalize();
        }
        String path;
        try {
            Path existing = absolute;
            Deque<String> below = new ArrayDeque<>();
            while (existing != null && !Files.exists(existing)) {
                Path name = existing.getFileName();
                if (name != null) below.push(name.toString());
                existing = existing.getParent();
            }
            Path real = existing == null ? absolute.getRoot() : existing.toRealPath();
            if (real == null) {
                path = absolute.toString();
            } else {
                for (String name : below) real = real.resolve(name);
                path = real.toString();
            }
        } catch (Exception e) {
            path = absolute.toString();
        }
        return File.separatorChar == '\\' ? path.toLowerCase(Locale.ROOT) : path;
    }
}
