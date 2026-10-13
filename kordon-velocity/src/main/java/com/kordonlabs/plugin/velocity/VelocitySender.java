package com.kordonlabs.plugin.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.kordonlabs.plugin.common.platform.Sender;
import com.kordonlabs.plugin.common.text.Legacy;
import com.kordonlabs.plugin.velocity.text.VelocityText;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * The source of a {@code /kordon} command on Velocity: a player, or the console (any source that is not a
 * {@link Player}). The console gets plain text so the log has no colour codes. Thread-safe, because the core replies
 * from its HTTP threads.
 */
public final class VelocitySender implements Sender {

    private final CommandSource source;
    private final Player player;

    public VelocitySender(CommandSource source) {
        this.source = source;
        this.player = source instanceof Player p ? p : null;
    }

    @Override
    public String name() {
        return player != null ? player.getUsername() : "console";
    }

    @Override
    public UUID uuid() {
        return player != null ? player.getUniqueId() : null;
    }

    @Override
    public boolean console() {
        return player == null;
    }

    @Override
    public boolean hasPermission(String node) {
        return source.hasPermission(node);
    }

    @Override
    public void send(String legacySection) {
        if (player != null) {
            player.sendMessage(VelocityText.chat(legacySection)); // may contain player-chosen text, so no links
        } else {
            source.sendMessage(Component.text(Legacy.strip(legacySection)));
        }
    }

    /** Players get a clickable link with a hover text; the console gets the text followed by the URL. */
    @Override
    public void sendLink(String legacySection, String url, String hoverLegacySection) {
        if (player == null) {
            send(Legacy.strip(legacySection) + " " + url);
            return;
        }
        player.sendMessage(VelocityText.link(legacySection, url, hoverLegacySection));
    }
}
