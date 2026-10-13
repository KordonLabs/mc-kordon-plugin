package com.kordonlabs.plugin.common.login;

import java.util.UUID;

/**
 * One login to decide. The platform listener builds it before holding the login, so everything that can fail happens
 * first.
 *
 * @param connectionKey identity of the connection (Bungee: the {@code PendingConnection}; Velocity: the
 *                      {@code Player}); the same object as {@code OnlinePlayer.connectionKey()} after PostLogin
 * @param ip            canonical host address
 * @param skin          online mode only; nullable
 * @param bedrock       a Floodgate player
 * @param host          the address the player joined by ({@code PlayerFacts.joinHost}); nullable
 */
public record LoginAttempt(Object connectionKey, String name, UUID uuid, String ip, int protocol, Skin skin,
                           boolean bedrock, String host) {

    public LoginAttempt(Object connectionKey, String name, UUID uuid, String ip, int protocol, Skin skin,
                        boolean bedrock) {
        this(connectionKey, name, uuid, ip, protocol, skin, bedrock, null);
    }
}
