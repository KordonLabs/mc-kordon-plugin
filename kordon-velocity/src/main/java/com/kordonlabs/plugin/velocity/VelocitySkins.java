package com.kordonlabs.plugin.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;
import com.kordonlabs.plugin.common.login.Skin;
import com.kordonlabs.plugin.common.login.SkinTextures;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the Mojang skin of an online-mode login from the game profile's {@code textures} property. Errors give null
 * and are logged once at debug level.
 */
public final class VelocitySkins {

    private final ThrottledLog log;
    private final AtomicBoolean reported = new AtomicBoolean();

    public VelocitySkins(ThrottledLog log) {
        this.log = log;
    }

    /** The skin of this login; null in offline mode, without a textures property, or on any error. */
    public Skin read(Player player) {
        try {
            if (!player.isOnlineMode()) return null;
            for (GameProfile.Property property : player.getGameProfileProperties()) {
                if (property != null && "textures".equals(property.getName())) {
                    return SkinTextures.parse(property.getValue());
                }
            }
            return null;
        } catch (Throwable t) {
            if (reported.compareAndSet(false, true) && log != null) {
                log.debug("Kordon: skin not read (" + t + ")");
            }
            return null;
        }
    }
}
