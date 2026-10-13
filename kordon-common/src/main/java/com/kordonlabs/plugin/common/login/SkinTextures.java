package com.kordonlabs.plugin.common.login;

import com.kordonlabs.plugin.common.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the skin from the Mojang {@code textures} profile property of an online-mode login. The value is base64 JSON
 * holding {@code textures.SKIN.url} and its {@code metadata.model}. Each platform finds the property its own way.
 */
public final class SkinTextures {

    static final Pattern TEXTURE_URL = Pattern.compile("^https?://textures\\.minecraft\\.net/texture/[0-9a-f]+$");

    private SkinTextures() {
    }

    /** Parses the base64 property value; null unless it names a Mojang skin URL. */
    public static Skin parse(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        String json;
        try {
            json = new String(Base64.getMimeDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Payload payload = Json.fromJson(json, Payload.class);
        if (payload == null || payload.textures() == null) return null;
        Texture skin = payload.textures().get("SKIN");
        if (skin == null || skin.url() == null || !TEXTURE_URL.matcher(skin.url()).matches()) return null;
        boolean slim = skin.metadata() != null && "slim".equals(skin.metadata().model());
        return new Skin(skin.url(), slim ? "slim" : "classic");
    }

    /** {@code {"textures":{"SKIN":{"url":"…","metadata":{"model":"slim"}},"CAPE":{…}}}} (other fields ignored). */
    record Payload(Map<String, Texture> textures) {
    }

    record Texture(String url, Metadata metadata) {
    }

    record Metadata(String model) {
    }
}
