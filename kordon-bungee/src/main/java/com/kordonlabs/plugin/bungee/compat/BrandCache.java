package com.kordonlabs.plugin.bungee.compat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client brands seen by {@code BrandListener} on proxies without {@code ProxiedPlayer#getClientBrand()}, keyed by the
 * {@code PendingConnection} (the proxy's InitialHandler uses identity equality). Keys are weak so a connection whose
 * disconnect event was missed does not leak. Thread-safe.
 */
public final class BrandCache {

    private final Map<Object, String> brands = Collections.synchronizedMap(new WeakHashMap<>());

    public void put(Object connection, String brand) {
        if (connection == null || brand == null) return;
        brands.put(connection, brand);
    }

    /** Null when the client has not sent its brand, or on 1.21+ proxies, which keep it themselves. */
    public String get(Object connection) {
        return connection == null ? null : brands.get(connection);
    }

    public void forget(Object connection) {
        if (connection != null) brands.remove(connection);
    }

    public void clear() {
        brands.clear();
    }
}
