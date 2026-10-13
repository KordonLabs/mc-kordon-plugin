package com.kordonlabs.plugin.common.api.dto;

import java.util.List;

/**
 * 200 answer of {@code POST /sync}.
 *
 * @param accepted        valid events processed
 * @param skipped         invalid events or unknown {@code t} (skipped, never a 400)
 * @param kicks           at most 100 kick orders for this instance; never null after parsing
 * @param resync          snapshot reconciliation; never null after parsing
 * @param syncIntervalSec pacing, clamped like the heartbeat's {@code features.sync}
 * @param settingsVersion when it differs from the cached one, a heartbeat is sent at once
 */
public record SyncResponse(boolean ok, int accepted, int skipped, List<Kick> kicks, Resync resync,
                           Integer syncIntervalSec, Integer idleSyncIntervalSec, Integer snapshotSec,
                           String settingsVersion) {

    public SyncResponse {
        kicks = kicks == null ? List.of() : kicks;
        resync = resync == null ? new Resync(List.of(), List.of()) : resync;
    }

    /**
     * @param unknown listed sids the panel does not know; their join is re-sent (same sid, original time)
     * @param closed  listed sids whose session the panel already closed; the player gets a new sid and a fresh join
     */
    public record Resync(List<String> unknown, List<String> closed) {

        public Resync {
            unknown = unknown == null ? List.of() : unknown;
            closed = closed == null ? List.of() : closed;
        }
    }

    public boolean wellFormed() {
        return ok;
    }
}
