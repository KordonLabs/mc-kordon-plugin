package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * Body of {@code POST /api/v1/mc/sync}. Nulls are omitted. The panel finds the previous run of this plugin copy by
 * the heartbeat's {@code node}.
 *
 * @param instance this enable's random instance UUID
 * @param events   at most {@code maxEvents}; may be empty (a poll)
 * @param online   the full snapshot of this instance's open sids, or null when not due
 * @param stopping the final sync on stop, or of the old instance on {@code /kordon link}
 */
public record SyncRequest(String instance, List<SyncEvent> events, Online online, boolean stopping) {

    /** @param ago milliseconds since the snapshot was taken */
    public record Online(long ago, List<String> sids) {
    }
}
