package com.kordonlabs.plugin.bungee;

import com.kordonlabs.plugin.bungee.compat.BrandCache;
import com.kordonlabs.plugin.bungee.compat.BrandDecoder;
import com.kordonlabs.plugin.bungee.compat.Reflect;
import com.kordonlabs.plugin.bungee.text.BungeeText;
import com.kordonlabs.plugin.common.compat.FloodgateHook;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.PlayerFacts;
import java.lang.invoke.MethodHandle;
import java.util.UUID;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;

/**
 * A connected proxy player. The core keeps the wrapper it got at PostLogin and later kicks exactly this connection.
 * Getters read the live {@link ProxiedPlayer} (brand, locale and backend change during the session); the IP is fixed
 * at creation. Thread-safe.
 */
public final class BungeeOnlinePlayer implements OnlinePlayer {

    private final ProxiedPlayer player;
    private final PendingConnection connection;
    private final String ip;
    private final BrandCache brands;
    private final FloodgateHook floodgate;
    private volatile Boolean bedrock;

    private BungeeOnlinePlayer(ProxiedPlayer player, String ip, BrandCache brands, FloodgateHook floodgate) {
        this.player = player;
        this.connection = player.getPendingConnection();
        this.ip = ip;
        this.brands = brands;
        this.floodgate = floodgate;
    }

    /** Wraps {@code player}; null when the connection is not TCP (without an IP the player is not tracked). */
    public static BungeeOnlinePlayer of(ProxiedPlayer player, BrandCache brands, FloodgateHook floodgate) {
        String ip = PlayerFacts.ipOf(player.getSocketAddress());
        return ip == null ? null : new BungeeOnlinePlayer(player, ip, brands, floodgate);
    }

    /** The wrapped proxy player (for the public kick event). */
    public ProxiedPlayer handle() {
        return player;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public String ip() {
        return ip;
    }

    @Override
    public int protocol() {
        return connection.getVersion();
    }

    @Override
    public String backend() {
        Server server = player.getServer();
        if (server == null || server.getInfo() == null) return null;
        return PlayerFacts.backend(server.getInfo().getName());
    }

    /** {@code getClientBrand()} where the proxy has it (1.21+, XCord), otherwise what {@code BrandListener} saw. */
    @Override
    public String clientBrand() {
        MethodHandle getClientBrand = Reflect.CLIENT_BRAND;
        if (getClientBrand != null) {
            try {
                String brand = BrandDecoder.clean((String) getClientBrand.invoke(player));
                if (brand != null) return brand;
            } catch (Throwable t) {
                // fall back to the plugin-message cache
            }
        }
        return brands == null ? null : brands.get(connection);
    }

    /** {@code getLocale()} as a BCP 47 tag; null before the client sent its settings, or when it is unparsable. */
    @Override
    public String locale() {
        return PlayerFacts.localeTag(player.getLocale());
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

    /** The same {@code PendingConnection} that {@code LoginEvent#getConnection()} returned for this login. */
    @Override
    public Object connectionKey() {
        return connection;
    }

    /** False as soon as the channel closes; BungeeCord marks it closed before it fires PlayerDisconnectEvent. */
    @Override
    public boolean connected() {
        return player.isConnected();
    }

    @Override
    public boolean hasPermission(String node) {
        return player.hasPermission(node);
    }

    @Override
    public void sendMessage(String legacySection) {
        player.sendMessage(BungeeText.chat(legacySection));
    }

    @Override
    public void disconnect(String legacySection) {
        player.disconnect(BungeeText.of(legacySection));
    }

    @Override
    public String toString() {
        return "BungeeOnlinePlayer{" + player.getName() + "}";
    }
}
