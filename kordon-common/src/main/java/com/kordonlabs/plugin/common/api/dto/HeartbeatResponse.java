package com.kordonlabs.plugin.common.api.dto;

/**
 * 200 answer of {@code POST /heartbeat}. Older panels omit {@code features}; sync, kicks, lists and lookup are then
 * unavailable.
 *
 * @param mode                 "protect" | "monitor"
 * @param failOpen             the "If the check is unavailable" setting: true lets players in when a login check fails
 * @param heartbeatIntervalSec the plugin clamps it to 15..300
 * @param settingsVersion      12 hex chars; a different value in a sync answer triggers an immediate heartbeat
 * @param features             null on an older panel
 * @param plugin               the plugin release the panel offers and this server's Plugin page in the panel
 * @param notices              staff notices for when the plugin decides on its own; null on older panels
 */
public record HeartbeatResponse(boolean ok, Server server, String mode, Boolean failOpen, Messages messages,
                                Integer heartbeatIntervalSec, String settingsVersion, Features features,
                                Plugin plugin, Notices notices) {

    /** Without {@code notices}, as sent by older panels. */
    public HeartbeatResponse(boolean ok, Server server, String mode, Boolean failOpen, Messages messages,
                             Integer heartbeatIntervalSec, String settingsVersion, Features features, Plugin plugin) {
        this(ok, server, mode, failOpen, messages, heartbeatIntervalSec, settingsVersion, features, plugin, null);
    }

    /**
     * @param blocked the staff chat line ({@code &}-codes; the plugin fills in {NICK} and {IP}) about a player the
     *                plugin refused on its own (fail-closed); null when that notice is off
     */
    public record Notices(String blocked) {
    }

    public record Server(String id, String name) {
    }

    /** Kick texts ({@code &}-codes) configured in the panel; {@code checkFailed} is the built-in one. */
    public record Messages(String vpn, String blacklist, String country, String checkFailed) {
    }

    /**
     * What the panel supports. A null {@code sync} means there is no sync endpoint. For {@code lists} and
     * {@code lookup}, a present key means the endpoint exists and the value is the in-game commands setting; the plugin
     * still calls the endpoint when it is false and shows the panel's {@code 403 commands_disabled}.
     */
    public record Features(Sync sync, Boolean lists, Boolean lookup) {
    }

    /**
     * Sync pacing. The plugin clamps it: intervalSec 2..60, idleIntervalSec 5..300, snapshotSec 30..900,
     * maxEvents 50..500, maxBodyBytes 65536..524288.
     */
    public record Sync(Integer intervalSec, Integer idleIntervalSec, Integer snapshotSec, Integer maxEvents,
                       Integer maxBodyBytes) {
    }

    /**
     * @param latestVersion version of the jar the panel serves; null when it bundles none
     * @param page          this server's Plugin page in the panel
     */
    public record Plugin(String latestVersion, String page) {
    }

    /** A 200 body the plugin can act on; otherwise the call is treated as UNSUPPORTED. */
    public boolean wellFormed() {
        return ok && messages != null;
    }
}
