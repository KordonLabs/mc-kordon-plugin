package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * 200 answer of {@code POST /lists}.
 *
 * @param result           "added" | "updated" | "removed" | "not_found"
 * @param lines            1..5 ready chat lines ({@code &}-codes, prefix included); never null after parsing
 * @param entry            the rule as stored; null for "not_found"
 * @param removedFromOther the value was removed from the other list
 * @param kicks            blacklist add only: kick orders for the calling instance's sessions, executed at once
 * @param kickedElsewhere  marked sessions on other proxies
 */
public record ListsResponse(boolean ok, String result, List<String> lines, Entry entry, boolean removedFromOther,
                            List<Kick> kicks, int kickedElsewhere) {

    public static final String ADDED = "added";
    public static final String UPDATED = "updated";
    public static final String REMOVED = "removed";
    public static final String NOT_FOUND = "not_found";

    public ListsResponse {
        lines = lines == null ? List.of() : lines;
        kicks = kicks == null ? List.of() : kicks;
    }

    /**
     * @param kind      "name" | "uuid" | "ip" | "cidr"
     * @param expiresAt ISO-8601, or null when it never expires
     */
    public record Entry(String list, String kind, String value, String display, String note, String expiresAt) {
    }

    public boolean wellFormed() {
        return ok && result != null;
    }
}
