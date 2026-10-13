package com.kordonlabs.plugin.common.session;

import java.util.List;

/**
 * One sync request's worth of work, taken atomically with the queue: the events (a peeked prefix), the open sids
 * (only when a snapshot was due and this batch drains the queue, otherwise null) and how many events to commit after
 * a successful sync.
 */
public record SyncBatch(List<SessionEvent> events, List<String> onlineSids, int drained) {
}
