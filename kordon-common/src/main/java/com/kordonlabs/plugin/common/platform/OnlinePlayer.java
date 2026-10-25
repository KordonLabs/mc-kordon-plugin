package com.kordonlabs.plugin.common.platform;

import com.kordonlabs.plugin.common.api.dto.ClientSettings;
import java.util.UUID;

/**
 * One live connection of a player. The core stores the wrapper it got at join and later kicks exactly that connection,
 * never a fresh lookup by UUID, because a reconnect of the same account is another connection. Implementations must be
 * thread-safe; getters may be called long after the join.
 */
public interface OnlinePlayer {

    UUID uuid();

    String name();

    /** Canonical host address ({@code %scope} stripped), never null: non-Inet connections are not tracked. */
    String ip();

    int protocol();

    /** Current backend server name; null while not connected to one. */
    String backend();

    /** Client brand such as {@code vanilla} or {@code fabric}; null until the client sent it. */
    String clientBrand();

    /** BCP 47 tag such as {@code en-US}; null until the client sent its settings, and for {@code und}. */
    String locale();

    /** A Floodgate (Bedrock) player. */
    boolean bedrock();

    /** The client's settings; null until the client sent them. */
    default ClientSettings clientSettings() {
        return null;
    }

    /** The current ping in ms; 0 or less while unknown. */
    default int ping() {
        return -1;
    }

    /**
     * Identity of the connection: the same object as {@code LoginAttempt.connectionKey} (Bungee: the
     * PendingConnection; Velocity: the Player).
     */
    Object connectionKey();

    /**
     * Whether the connection is still open. False once the proxy closed it, which can be before its disconnect event
     * reaches Kordon's handler. Thread-safe.
     */
    boolean connected();

    boolean hasPermission(String node);

    /** A chat line with {@code §} codes already translated. */
    void sendMessage(String legacySection);

    /** Disconnects with a kick screen ({@code §} codes, {@code \n} allowed). Thread-safe. */
    void disconnect(String legacySection);
}
