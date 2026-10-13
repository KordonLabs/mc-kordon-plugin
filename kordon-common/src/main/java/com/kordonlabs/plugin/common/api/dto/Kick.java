package com.kordonlabs.plugin.common.api.dto;

/**
 * A kick order from the panel, part of sync and lists answers. It is repeated on every sync until the session ends;
 * the plugin disconnects the connection tracked under {@code sid}.
 *
 * @param message the kick screen ({@code &}-codes, {@code \n}, at most 256 chars)
 * @param reason  "panel" | "blacklist" | "game"
 */
public record Kick(String sid, String message, String reason) {
}
