package com.kordonlabs.plugin.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.SkinParts;
import com.kordonlabs.plugin.common.api.dto.ClientSettings;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.PlayerFacts;
import com.kordonlabs.plugin.velocity.text.VelocityText;
import java.util.Optional;
import java.util.UUID;

/**
 * A connected player on a Velocity proxy. The connection key is the {@link Player} itself, which stays the same
 * object from {@code LoginEvent} to {@code DisconnectEvent}, so a later kick reaches exactly this connection. Getters
 * read the live player; the IP is fixed at creation. Thread-safe.
 */
public final class VelocityOnlinePlayer implements OnlinePlayer {

    private final Player player;
    private final String ip;
    private final FloodgateHook floodgate;
    private volatile Boolean bedrock;

    private VelocityOnlinePlayer(Player player, String ip, FloodgateHook floodgate) {
        this.player = player;
        this.ip = ip;
        this.floodgate = floodgate;
    }

    /** Wraps {@code player}; null when the connection has no IP address and is therefore not tracked. */
    public static VelocityOnlinePlayer of(Player player, FloodgateHook floodgate) {
        String ip = PlayerFacts.ipOf(player.getRemoteAddress());
        return ip == null ? null : new VelocityOnlinePlayer(player, ip, floodgate);
    }

    /** The wrapped proxy player, used for the public kick event. */
    public Player handle() {
        return player;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public String ip() {
        return ip;
    }

    @Override
    public int protocol() {
        return player.getProtocolVersion().getProtocol();
    }

    @Override
    public String backend() {
        Optional<ServerConnection> server = player.getCurrentServer();
        return server.isEmpty() ? null : PlayerFacts.backend(server.get().getServerInfo().getName());
    }

    /** The raw client brand, null until the client sends it; the tracker sanitises it. */
    @Override
    public String clientBrand() {
        return player.getClientBrand();
    }

    /** Null until the client sends its settings; before that Velocity returns a default {@code en_US} object. */
    @Override
    public String locale() {
        return localeOf(player);
    }

    static String localeOf(Player player) {
        return player.hasSentPlayerSettings() ? PlayerFacts.localeTag(player.getPlayerSettings().getLocale()) : null;
    }

    @Override
    public ClientSettings clientSettings() {
        if (!player.hasSentPlayerSettings()) return null;
        PlayerSettings s = player.getPlayerSettings();
        SkinParts skin = s.getSkinParts();
        return new ClientSettings((int) s.getViewDistance(), ClientSettings.token(s.getChatMode()), s.hasChatColors(),
                ClientSettings.token(s.getMainHand()),
                skin == null ? null : ClientSettings.skinMask(skin.hasCape(), skin.hasJacket(), skin.hasLeftSleeve(),
                        skin.hasRightSleeve(), skin.hasLeftPants(), skin.hasRightPants(), skin.hasHat()),
                s.isClientListingAllowed(), s.isTextFilteringEnabled(), ClientSettings.token(s.getParticleStatus()));
    }

    /** Keep-alive round trip in ms (-1 until known). */
    @Override
    public int ping() {
        long ping = player.getPing();
        return ping > Integer.MAX_VALUE ? -1 : (int) ping;
    }

    @Override
    public boolean bedrock() {
        Boolean known = bedrock;
        if (known == null) {
            known = floodgate != null && floodgate.isBedrock(player.getUniqueId());
            bedrock = known;
        }
        return known;
    }

    /** The same {@link Player} object that {@code LoginEvent#getPlayer()} returned for this login. */
    @Override
    public Object connectionKey() {
        return player;
    }

    /** False once the channel is closed; Velocity fires DisconnectEvent only after that. */
    @Override
    public boolean connected() {
        return player.isActive();
    }

    @Override
    public boolean hasPermission(String node) {
        return player.hasPermission(node);
    }

    @Override
    public void sendMessage(String legacySection) {
        player.sendMessage(VelocityText.chat(legacySection));
    }

    @Override
    public void disconnect(String legacySection) {
        player.disconnect(VelocityText.of(legacySection));
    }

    @Override
    public String toString() {
        return "VelocityOnlinePlayer{" + player.getUsername() + "}";
    }
}
