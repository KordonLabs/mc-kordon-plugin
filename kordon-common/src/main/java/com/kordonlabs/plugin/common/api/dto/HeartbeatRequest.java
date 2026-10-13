package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * Body of {@code POST /api/v1/mc/heartbeat}. Null fields are not sent.
 *
 * @param instance      this enable's random instance UUID (lower-case, dashed)
 * @param node          identifies this plugin copy: a name-based UUID of the machine name and the plugin folder path
 * @param host          the machine's host name (at most 64 chars); null when unknown
 * @param pingMs        median response time of the requests answered in the last 60 s; null when there were none
 */
public record HeartbeatRequest(String platform, String serverVersion, String pluginVersion, boolean onlineMode,
                               int playersOnline, String instance, List<String> features, String node, String host,
                               Integer pingMs) {
}
