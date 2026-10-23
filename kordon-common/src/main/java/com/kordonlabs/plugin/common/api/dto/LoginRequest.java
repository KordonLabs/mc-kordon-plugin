package com.kordonlabs.plugin.common.api.dto;

/**
 * Body of {@code POST /api/v1/mc/login}. The client brand is not known yet at login and arrives later in a sync
 * {@code info} event. Nulls are omitted.
 *
 * @param name     as the proxy sees it, Floodgate prefix included ({@code .Steve})
 * @param ip       canonical host address
 * @param uuid     dashed UUID as the proxy sees it (offline, online or Floodgate)
 * @param protocol the client's protocol number
 * @param skin     online mode only; null otherwise
 * @param host     the address the player typed to join (the handshake's virtual host, e.g. {@code play.example.com});
 *                 null when the proxy does not know it
 * @param bedrock  {@code true} for a Floodgate player, else null
 */
public record LoginRequest(String name, String ip, String uuid, Integer protocol, Skin skin, String host,
                           Boolean bedrock) {

    public LoginRequest(String name, String ip, String uuid, Integer protocol, Skin skin, String host) {
        this(name, ip, uuid, protocol, skin, host, null);
    }

    public LoginRequest(String name, String ip, String uuid, Integer protocol, Skin skin) {
        this(name, ip, uuid, protocol, skin, null, null);
    }

    /** {@code texture} is a {@code http://textures.minecraft.net/texture/...} URL; {@code model} is slim or classic. */
    public record Skin(String texture, String model) {
    }
}
