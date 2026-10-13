package com.kordonlabs.plugin.common.api.dto;

/**
 * Body of {@code POST /api/v1/mc/lists}, sent by {@code /kordon whitelist|blacklist add|remove}. Nulls are omitted.
 *
 * @param op       "add" | "remove"
 * @param list     "whitelist" | "blacklist"
 * @param value    1..64 chars: nick, UUID, IP or CIDR range
 * @param kind     "auto" | "name" | "ip" ({@code --name} / {@code --ip})
 * @param expiry   add only: "never" | "1h" | "1d" | "7d" | "30d"; null for remove
 * @param note     add only, at most 200 chars; nullable
 * @param instance the calling instance; kicks for its sessions come back in the answer
 */
public record ListsRequest(String op, String list, String value, String kind, String expiry, String note, Actor actor,
                           String instance) {

    public static final String ADD = "add";
    public static final String REMOVE = "remove";
    public static final String WHITELIST = "whitelist";
    public static final String BLACKLIST = "blacklist";
}
