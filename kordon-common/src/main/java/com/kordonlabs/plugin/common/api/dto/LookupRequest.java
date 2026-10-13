package com.kordonlabs.plugin.common.api.dto;

/**
 * Body of {@code POST /api/v1/mc/lookup}, sent by {@code /kordon lookup <name|UUID|IP>}.
 *
 * @param query  trimmed, 1..64 chars
 * @param showIp the sender has {@code kordon.command.lookup.ip}, which allows IP queries and IP lines
 */
public record LookupRequest(String query, boolean showIp, Actor actor) {
}
