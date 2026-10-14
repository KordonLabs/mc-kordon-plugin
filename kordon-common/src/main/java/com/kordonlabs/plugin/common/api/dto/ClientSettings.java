package com.kordonlabs.plugin.common.api.dto;

import java.util.Locale;

/**
 * The client's settings: {@code settings} of a sync {@code join} or {@code info}. Every field is optional; BungeeCord
 * does not report {@code listing}, {@code filtering} or {@code particles}.
 *
 * @param viewDistance render distance in chunks
 * @param chatMode     "shown", "commands_only" or "hidden"
 * @param chatColors   chat colours enabled
 * @param mainHand     "left" or "right"
 * @param skinParts    shown skin layers as a bitmask: cape 1, jacket 2, left sleeve 4, right sleeve 8, left pants 16,
 *                     right pants 32, hat 64
 * @param listing      the player allows being listed in the server list
 * @param filtering    text filtering enabled
 * @param particles    "all", "decreased" or "minimal"
 */
public record ClientSettings(Integer viewDistance, String chatMode, Boolean chatColors, String mainHand, Integer skinParts,
                             Boolean listing, Boolean filtering, String particles) {

    public static final int CAPE = 1;
    public static final int JACKET = 2;
    public static final int LEFT_SLEEVE = 4;
    public static final int RIGHT_SLEEVE = 8;
    public static final int LEFT_PANTS = 16;
    public static final int RIGHT_PANTS = 32;
    public static final int HAT = 64;

    public static int skinMask(boolean cape, boolean jacket, boolean leftSleeve, boolean rightSleeve, boolean leftPants,
                               boolean rightPants, boolean hat) {
        return (cape ? CAPE : 0) | (jacket ? JACKET : 0) | (leftSleeve ? LEFT_SLEEVE : 0) | (rightSleeve ? RIGHT_SLEEVE : 0)
                | (leftPants ? LEFT_PANTS : 0) | (rightPants ? RIGHT_PANTS : 0) | (hat ? HAT : 0);
    }

    /** An enum constant as sent on the wire: {@code COMMANDS_ONLY} becomes "commands_only". */
    public static String token(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
}
