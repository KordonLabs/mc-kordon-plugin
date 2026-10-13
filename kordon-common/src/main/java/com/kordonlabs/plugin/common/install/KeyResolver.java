package com.kordonlabs.plugin.common.install;

import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Picks the server key at start: {@code plugins/Kordon/secret.key} ({@link KeyFile}) first, else the key built into
 * the personal Kordon.jar, which is then saved to {@code secret.key}.
 *
 * <p>When both exist and differ, the jar's key wins only if Kordon.jar was modified after {@code secret.key}: that is
 * a freshly downloaded jar, for example after a key rotation. Otherwise the file wins, for example after
 * {@code /kordon link}. Console lines name key prefixes only, never a key.
 */
public final class KeyResolver {

    /**
     * @param token  the key in use; null exactly when the source is {@link KeySource#NONE}
     * @param saved  {@code secret.key} now holds {@code token}
     */
    public record Resolution(String token, KeySource source, boolean saved) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Resolution[token=" + Tokens.prefix(token) + "…, source=" + source + ", saved=" + saved + "]";
        }
    }

    private final KeyFile file;
    private final Path pluginJar;
    private final ThrottledLog log;
    private final Messages texts;

    public KeyResolver(KeyFile file, Path pluginJar, ThrottledLog log, Messages texts) {
        this.file = file;
        this.pluginJar = pluginJar;
        this.log = log;
        this.texts = texts;
    }

    /** Never throws. */
    public Resolution resolve(InstallInfo embedded) {
        String jarKey = embedded != null && embedded.present() ? embedded.token() : null;
        KeyFile.Read read = file.read();
        if (read instanceof KeyFile.Valid valid) {
            String fileKey = valid.token();
            if (jarKey == null || jarKey.equals(fileKey)) return new Resolution(fileKey, KeySource.FILE, true);
            if (jarNewer()) {
                boolean saved = save(jarKey);
                if (saved) {
                    log.info(texts.console(MessageKey.LOG_KEY_REPLACED, "tokenPrefix", Tokens.prefix(jarKey),
                            "oldPrefix", Tokens.prefix(fileKey)));
                }
                return new Resolution(jarKey, KeySource.JAR, saved);
            }
            log.info(texts.console(MessageKey.LOG_KEY_KEPT, "tokenPrefix", Tokens.prefix(fileKey),
                    "jarPrefix", Tokens.prefix(jarKey)));
            return new Resolution(fileKey, KeySource.FILE, true);
        }
        if (read instanceof KeyFile.Invalid invalid) {
            log.warn(texts.console(MessageKey.LOG_KEY_INVALID));
            log.debug("secret.key ignored: " + invalid.why());
        }
        if (jarKey == null) return new Resolution(null, KeySource.NONE, false);
        boolean saved = save(jarKey);
        if (saved) log.info(texts.console(MessageKey.LOG_KEY_SAVED, "tokenPrefix", Tokens.prefix(jarKey)));
        return new Resolution(jarKey, KeySource.JAR, saved);
    }

    /** Kordon.jar was modified strictly after {@code secret.key}; false if either time is unreadable. */
    boolean jarNewer() {
        try {
            FileTime keyTime = file.modified();
            if (keyTime == null || pluginJar == null || !Files.isRegularFile(pluginJar)) return false;
            return Files.getLastModifiedTime(pluginJar).compareTo(keyTime) > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean save(String token) {
        try {
            file.write(token);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn(texts.console(MessageKey.LOG_KEY_SAVE_FAILED, "error", e.getClass().getSimpleName()));
            return false;
        }
    }
}
