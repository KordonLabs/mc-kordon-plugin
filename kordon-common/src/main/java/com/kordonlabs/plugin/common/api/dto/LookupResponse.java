package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * 200 answer of {@code POST /lookup}.
 *
 * @param found "player" | "ip" | "none"
 * @param lines 1..20 ready chat lines; never null after parsing
 * @param url   the panel page of the player or IP, shown to players as a clickable link; null for "none"
 */
public record LookupResponse(boolean ok, String found, List<String> lines, String url) {

    public LookupResponse {
        lines = lines == null ? List.of() : lines;
    }

    public boolean wellFormed() {
        return ok && found != null;
    }
}
