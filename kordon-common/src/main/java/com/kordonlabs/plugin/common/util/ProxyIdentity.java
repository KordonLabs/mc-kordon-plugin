package com.kordonlabs.plugin.common.util;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * This proxy's identity for the heartbeat, computed once per enable: the machine name ({@link MachineName}) and the
 * {@link NodeId} derived from it. The first call may block on file I/O or DNS, so never make it on a proxy thread.
 * Thread-safe; never throws.
 */
public final class ProxyIdentity {

    private final Path dataFolder;
    private final Supplier<String> hostName;
    private boolean hostResolved;
    private String host;
    private String node;

    /** @param hostName supplies the machine name; may be null when unknown */
    public ProxyIdentity(Path dataFolder, Supplier<String> hostName) {
        this.dataFolder = dataFolder;
        this.hostName = hostName;
    }

    /** The machine name, resolved once; null when unknown. */
    public synchronized String host() {
        if (!hostResolved) {
            hostResolved = true;
            try {
                host = hostName == null ? null : hostName.get();
            } catch (Throwable t) {
                host = null;
            }
        }
        return host;
    }

    public synchronized String node() {
        if (node == null) node = NodeId.derive(host(), dataFolder);
        return node;
    }
}
