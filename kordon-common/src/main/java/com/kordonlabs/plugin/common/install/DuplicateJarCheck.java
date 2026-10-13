package com.kordonlabs.plugin.common.install;

import com.kordonlabs.plugin.common.json.Json;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Warns about a second Kordon jar in {@code plugins/}, typically left over after an update. The proxy loads only one
 * of them and which one is not obvious (BungeeCord takes the last, Velocity the first in directory order). A jar
 * counts as Kordon when it has a {@code kordon-install.json} entry, a {@code bungee.yml} with {@code name: Kordon}, or
 * a {@code velocity-plugin.json} whose top-level {@code id} is {@code kordon}. Never throws.
 */
public final class DuplicateJarCheck {

    private static final Pattern KORDON_NAME = Pattern.compile("^name:\\s*(['\"]?)Kordon\\1\\s*$");
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final String VELOCITY_DESCRIPTOR = "velocity-plugin.json";
    private static final String VELOCITY_ID = "kordon";

    private DuplicateJarCheck() {
    }

    /** Logs a warning listing all Kordon jars when {@link #find} returns anything. */
    public static void run(Path pluginsFolder, Path ownJar, Logger logger, Messages messages) {
        try {
            List<Path> others = find(pluginsFolder, ownJar);
            if (others.isEmpty()) return;
            List<Path> all = new ArrayList<>();
            if (ownJar != null) all.add(ownJar);
            all.addAll(others);
            String files = all.stream().map(p -> String.valueOf(p.getFileName())).collect(Collectors.joining(", "));
            logger.warning(messages.console(MessageKey.LOG_DUPLICATE_JARS, "files", files));
        } catch (Throwable t) {
            logger.log(Level.FINE, "Kordon: duplicate jar check failed", t);
        }
    }

    /** Other jars in {@code pluginsFolder} that are Kordon too (sorted by file name); unreadable files are skipped. */
    public static List<Path> find(Path pluginsFolder, Path ownJar) throws IOException {
        List<Path> found = new ArrayList<>();
        if (pluginsFolder == null || !Files.isDirectory(pluginsFolder)) return found;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginsFolder,
                p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))) {
            for (Path jar : jars) {
                if (!Files.isRegularFile(jar) || isSame(jar, ownJar)) continue;
                if (isKordon(jar)) found.add(jar);
            }
        }
        found.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return found;
    }

    static boolean isKordon(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(InstallReader.ENTRY) != null) return true;
            String yaml = descriptor(zip, "bungee.yml");
            if (yaml != null && yaml.lines().anyMatch(line -> KORDON_NAME.matcher(line.strip()).matches())) return true;
            // Only the top-level id: an add-on declaring {"dependencies":[{"id":"kordon",...}]} is not Kordon.
            VelocityDescriptor velocity = Json.fromJson(descriptor(zip, VELOCITY_DESCRIPTOR), VelocityDescriptor.class);
            return velocity != null && VELOCITY_ID.equals(velocity.id());
        } catch (IOException | RuntimeException e) {
            return false; // not a zip, locked or broken: not ours to judge
        }
    }

    /** The entry as UTF-8 text (at most 64 KiB); null when the jar has no such entry. */
    private static String descriptor(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readNBytes(MAX_DESCRIPTOR_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static boolean isSame(Path jar, Path ownJar) {
        if (ownJar == null) return false;
        try {
            return Files.isSameFile(jar, ownJar);
        } catch (IOException e) {
            return jar.toAbsolutePath().normalize().equals(ownJar.toAbsolutePath().normalize());
        }
    }

    /** {@code velocity-plugin.json}: only the top-level {@code id} matters. */
    private record VelocityDescriptor(String id) {
    }
}
