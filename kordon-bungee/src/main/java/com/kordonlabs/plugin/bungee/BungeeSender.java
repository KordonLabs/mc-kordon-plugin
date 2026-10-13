package com.kordonlabs.plugin.bungee;

import com.kordonlabs.plugin.bungee.text.BungeeText;
import com.kordonlabs.plugin.common.platform.Sender;
import com.kordonlabs.plugin.common.text.Legacy;
import java.util.UUID;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * Whoever typed {@code /kordon}: a player, or the console (any sender that is not a {@link ProxiedPlayer}). The
 * console gets plain text so no colour codes end up in the log file. Permissions are always the sender's own: the
 * proxy's console has every one, while a plugin's own sender (a Discord or web console bridge) has only what it was
 * given. Thread-safe, because the core replies from its HTTP threads.
 */
public final class BungeeSender implements Sender {

    private final CommandSender sender;
    private final ProxiedPlayer player;

    public BungeeSender(CommandSender sender) {
        this.sender = sender;
        this.player = sender instanceof ProxiedPlayer p ? p : null;
    }

    @Override
    public String name() {
        return player != null ? player.getName() : "console";
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
        return sender.hasPermission(node);
    }

    @Override
    public void send(String legacySection) {
        if (player != null) {
            player.sendMessage(BungeeText.chat(legacySection)); // lines may carry player-chosen text, so no auto-links
        } else {
            console(Legacy.strip(legacySection));
        }
    }

    /** Players get a clickable link with an optional hover text; the console gets {@code text url}. */
    @Override
    public void sendLink(String legacySection, String url, String hoverLegacySection) {
        if (player == null) {
            console(Legacy.strip(legacySection) + " " + url);
            return;
        }
        BaseComponent link = BungeeText.of(legacySection);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        if (hoverLegacySection != null && !hoverLegacySection.isEmpty()) {
            link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(new BaseComponent[] {BungeeText.of(hoverLegacySection)})));
        }
        player.sendMessage(link);
    }

    /**
     * Uses the deprecated String overload on purpose: Bungee's console turns a component into {@code toLegacyText()},
     * which starts with {@code §f} and ends up in the log file, while a String reaches the logger as is.
     */
    @SuppressWarnings("deprecation")
    private void console(String plain) {
        sender.sendMessage(plain);
    }
}
