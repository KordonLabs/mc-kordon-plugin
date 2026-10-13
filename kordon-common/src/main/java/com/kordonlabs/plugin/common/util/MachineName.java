package com.kordonlabs.plugin.common.util;

import java.io.BufferedReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The machine's host name, reported in the heartbeat and used to derive the {@link NodeId}. Takes the first non-blank
 * of the {@code HOSTNAME} and {@code COMPUTERNAME} environment variables, {@code /etc/hostname} and
 * {@code InetAddress.getLocalHost()}. May block on file I/O or DNS, so never call it on a proxy thread. Never throws.
 */
public final class MachineName {

    static final int MAX_LENGTH = 64;
    static final Path ETC_HOSTNAME = Path.of("/etc/hostname");

    private MachineName() {
    }

    /** This machine's name, or null; failures are reported as debug lines. */
    public static String resolve(Consumer<String> debug) {
        return resolve(System::getenv, () -> firstLine(ETC_HOSTNAME, debug), () -> localHostName(debug), debug);
    }

    /**
     * @param env          environment lookup ({@code System::getenv})
     * @param hostnameFile the first line of {@code /etc/hostname}, or null
     * @param localHost    {@code InetAddress.getLocalHost().getHostName()}, or null
     */
    static String resolve(Function<String, String> env, Supplier<String> hostnameFile, Supplier<String> localHost,
                          Consumer<String> debug) {
        String name = clean(safe(() -> env.apply("HOSTNAME"), debug));
        if (name == null) name = clean(safe(() -> env.apply("COMPUTERNAME"), debug));
        if (name == null) name = clean(safe(hostnameFile, debug));
        if (name == null) name = clean(safe(localHost, debug));
        return name;
    }

    /** Removes control characters, trims and cuts to {@link #MAX_LENGTH}; null when nothing is left. */
    static String clean(String value) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
        String s = out.toString().trim();
        if (s.length() > MAX_LENGTH) {
            int end = Character.isHighSurrogate(s.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            s = s.substring(0, end).trim();
        }
        return s.isEmpty() ? null : s;
    }

    private static String safe(Supplier<String> source, Consumer<String> debug) {
        try {
            return source.get();
        } catch (Throwable t) {
            if (debug != null) debug.accept("host name: " + t);
            return null;
        }
    }

    private static String firstLine(Path file, Consumer<String> debug) {
        if (!Files.isRegularFile(file)) return null;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return in.readLine();
        } catch (Exception e) {
            if (debug != null) debug.accept("host name: cannot read " + file + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String localHostName(Consumer<String> debug) {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            if (debug != null) debug.accept("host name: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
