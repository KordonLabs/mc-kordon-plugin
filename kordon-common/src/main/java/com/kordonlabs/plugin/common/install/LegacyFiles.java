package com.kordonlabs.plugin.common.install;

import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes the {@code state.json} that older plugin versions kept in {@code plugins/Kordon}, then the folder when
 * nothing is left in it. Never touches {@code config.yml} or {@code secret.key}. Never throws; failures are debug
 * lines.
 */
public final class LegacyFiles {

    static final String STATE = "state.json";
    static final String STATE_TMP = "state.json.tmp";

    private LegacyFiles() {
    }

    public static void cleanUp(Path dataFolder, ThrottledLog log) {
        if (dataFolder == null) return;
        try {
            if (!Files.isDirectory(dataFolder)) return;
            boolean removed = Files.deleteIfExists(dataFolder.resolve(STATE));
            removed |= Files.deleteIfExists(dataFolder.resolve(STATE_TMP));
            if (removed && log != null) log.debug("legacy state.json removed (1.8.0 keeps only secret.key)");
            KeyFile.removeFolderIfEmpty(dataFolder);
        } catch (Exception e) {
            if (log != null) log.debug("legacy state.json not removed: " + e.getClass().getSimpleName());
        }
    }
}
