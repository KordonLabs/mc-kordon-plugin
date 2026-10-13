package com.kordonlabs.plugin.bungee.compat;

import com.kordonlabs.plugin.common.login.Skin;
import com.kordonlabs.plugin.common.login.SkinTextures;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.util.concurrent.atomic.AtomicBoolean;
import net.md_5.bungee.api.connection.PendingConnection;

/**
 * Reads the Mojang skin of an online-mode login. The proxy API has no accessor, so the runtime class behind
 * {@code PendingConnection} (InitialHandler) is read by name: {@code getLoginProfile()}, then {@code getProperties()},
 * then the value of the {@code textures} property, which {@link SkinTextures} parses.
 *
 * <p>If the internals are not what we expect, skin reading is switched off for the rest of the run and one debug line
 * says why. Malformed textures only give null for that player.
 */
public final class SkinReader {

    private final ThrottledLog log;
    private final AtomicBoolean disabled = new AtomicBoolean();

    public SkinReader(ThrottledLog log) {
        this.log = log;
    }

    /** The skin of this login; null in offline mode, without a textures property, or when reading is switched off. */
    public Skin read(PendingConnection connection) {
        if (disabled.get()) return null;
        try {
            if (!connection.isOnlineMode()) return null;
            return fromHandler(connection);
        } catch (Throwable t) {
            if (disabled.compareAndSet(false, true) && log != null) {
                log.debug("Kordon: skins are not read on this proxy (" + t + ")");
            }
            return null;
        }
    }

    /** False after the first reflection failure of this run. */
    public boolean enabled() {
        return !disabled.get();
    }

    /** Reads the skin from an InitialHandler or a look-alike. Throws when the proxy internals changed. */
    static Skin fromHandler(Object handler) throws Throwable {
        Object profile = Reflect.call(handler, "getLoginProfile");
        if (profile == null) return null; // offline mode, or no Mojang profile
        Object properties = Reflect.call(profile, "getProperties");
        if (!(properties instanceof Object[] array)) return null;
        for (Object property : array) {
            if (property != null && "textures".equals(Reflect.call(property, "getName"))) {
                return Reflect.call(property, "getValue") instanceof String value ? SkinTextures.parse(value) : null;
            }
        }
        return null;
    }
}
