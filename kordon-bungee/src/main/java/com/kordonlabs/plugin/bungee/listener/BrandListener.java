package com.kordonlabs.plugin.bungee.listener;

import com.kordonlabs.plugin.bungee.compat.BrandCache;
import com.kordonlabs.plugin.bungee.compat.BrandDecoder;
import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * Captures the client brand on proxies without {@code ProxiedPlayer#getClientBrand()}; registered only when
 * {@code Reflect.CLIENT_BRAND} is absent. The brand message is only read, never cancelled or changed.
 */
public final class BrandListener implements Listener {

    private final KordonCore core;
    private final BrandCache brands;
    private final ThrottledLog log;

    public BrandListener(KordonCore core, BrandCache brands, ThrottledLog log) {
        this.core = core;
        this.brands = brands;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPluginMessage(PluginMessageEvent event) {
        try {
            if (!(event.getSender() instanceof ProxiedPlayer player) || !BrandDecoder.isBrandChannel(event.getTag())) {
                return;
            }
            String brand = BrandDecoder.decode(event.getData());
            if (brand == null) return;
            PendingConnection key = player.getPendingConnection();
            brands.put(key, brand);
            SessionTracker sessions = core.stopped() ? null : core.sessions();
            if (sessions != null) sessions.onBrand(key, brand);
        } catch (Throwable t) {
            log.warn("listener.PluginMessageEvent", "Kordon: client brand not read after an error: " + t);
        }
    }
}
