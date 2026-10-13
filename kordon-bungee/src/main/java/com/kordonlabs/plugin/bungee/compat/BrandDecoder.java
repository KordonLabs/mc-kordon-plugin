package com.kordonlabs.plugin.bungee.compat;

import java.nio.charset.StandardCharsets;

/**
 * Decodes the client brand from a {@code minecraft:brand} (1.13+) or {@code MC|Brand} (1.8 to 1.12) plugin message.
 * Both carry one Minecraft string: a VarInt byte length, then UTF-8 bytes.
 */
public final class BrandDecoder {

    public static final String CHANNEL = "minecraft:brand";
    public static final String LEGACY_CHANNEL = "MC|Brand";
    /** The panel rejects longer brands. */
    public static final int MAX_LENGTH = 128;
    /** Enough bytes for {@link #MAX_LENGTH} chars of any script (up to 4 bytes each); the rest is not decoded. */
    private static final int MAX_DECODED_BYTES = MAX_LENGTH * 4;

    private BrandDecoder() {
    }

    public static boolean isBrandChannel(String tag) {
        return CHANNEL.equals(tag) || LEGACY_CHANNEL.equals(tag);
    }

    /** The brand in {@code data}; null for null/empty data, a malformed or truncated string, or a blank brand. */
    public static String decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        int length = 0;
        int pos = 0;
        for (int shift = 0; ; shift += 7) {
            if (pos >= data.length || shift >= 35) return null; // truncated or longer than 5 bytes
            byte b = data[pos++];
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        if (length <= 0 || length > data.length - pos) return null;
        int decoded = Math.min(length, MAX_DECODED_BYTES);
        return clean(new String(data, pos, decoded, StandardCharsets.UTF_8));
    }

    /**
     * A brand as the proxy reports it ({@code getClientBrand()}) or as decoded: control characters removed, trimmed,
     * at most {@link #MAX_LENGTH} chars (never ending in half a surrogate pair); null when blank.
     */
    public static String clean(String brand) {
        if (brand == null) return null;
        StringBuilder out = new StringBuilder(brand.length());
        for (int i = 0; i < brand.length(); i++) {
            char c = brand.charAt(i);
            if (!Character.isISOControl(c)) out.append(c);
        }
        String s = out.toString().strip();
        if (s.length() > MAX_LENGTH) {
            int end = MAX_LENGTH;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            s = s.substring(0, end).strip();
        }
        return s.isEmpty() ? null : s;
    }
}
