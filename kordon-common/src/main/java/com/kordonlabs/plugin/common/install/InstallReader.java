package com.kordonlabs.plugin.common.install;

import com.kordonlabs.plugin.common.json.Json;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the {@code kordon-install.json} the panel appends to each personal download.
 *
 * <p>Opens the plugin's <em>own</em> jar with {@link ZipFile} rather than a class-loader lookup, because a parent-first
 * {@code getResource} may return another plugin's file. The entry is always decoded as UTF-8, never in the platform
 * charset, which differs between systems. A newer format version logs a warning and the known fields are still read.
 * A missing or invalid entry yields {@link InstallInfo#NONE}. Never throws and never logs the token.
 */
public final class InstallReader {

    /** The entry the panel appends to each personal download. */
    public static final String ENTRY = "kordon-install.json";
    /** The panel writes about 250 bytes; anything much larger is not ours. */
    static final int MAX_BYTES = 16 * 1024;

    /** The wire shape of {@code kordon-install.json}; unknown fields are ignored. */
    private record Payload(Integer v, String panelUrl, String token, String serverId, String serverName,
                           String issuedAt) {
    }

    private final ThrottledLog log;
    private final Messages messages;

    public InstallReader(ThrottledLog log) {
        this.log = log;
        this.messages = Messages.load();
    }

    public InstallInfo read(Path pluginJar) {
        try {
            if (pluginJar == null || !Files.isRegularFile(pluginJar)) {
                debug("no plugin jar at " + pluginJar);
                return InstallInfo.NONE;
            }
            String json = readEntry(pluginJar);
            if (json == null) {
                debug("no " + ENTRY + " in " + pluginJar.getFileName() + " (not a personal download)");
                return InstallInfo.NONE;
            }
            return parse(json);
        } catch (IOException | RuntimeException e) {
            debug("cannot read " + ENTRY + ": " + e);
            return InstallInfo.NONE;
        }
    }

    /** The entry decoded as UTF-8; null when absent. */
    static String readEntry(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream in = zip.getInputStream(entry);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder(512);
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    out.append(buf, 0, n);
                    if (out.length() > MAX_BYTES) {
                        throw new IOException(ENTRY + " is larger than " + MAX_BYTES + " chars");
                    }
                }
                return out.toString();
            }
        }
    }

    private InstallInfo parse(String json) {
        Payload p = Json.fromJson(json, Payload.class);
        if (p == null) {
            debug(ENTRY + " is not valid JSON");
            return InstallInfo.NONE;
        }
        if (p.v() == null || p.v() < 1) {
            debug(ENTRY + ": unknown format version " + p.v());
            return InstallInfo.NONE;
        }
        if (p.v() > 1) {
            log.warn(messages.console(MessageKey.LOG_INSTALL_NEWER, "v", p.v()));
        }
        String panelUrl = PanelUrls.normalize(p.panelUrl() == null ? null : p.panelUrl().trim());
        if (panelUrl == null) {
            debug(ENTRY + ": panelUrl is not a valid panel address");
            return InstallInfo.NONE;
        }
        String token = p.token() == null ? null : p.token().trim();
        if (!Tokens.valid(token)) {
            debug(ENTRY + ": token is not a valid server token");
            return InstallInfo.NONE;
        }
        return new InstallInfo(panelUrl, token, blankToNull(p.serverId()), blankToNull(p.serverName()),
                blankToNull(p.issuedAt()));
    }

    private void debug(String line) {
        if (log != null) log.debug(line);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
