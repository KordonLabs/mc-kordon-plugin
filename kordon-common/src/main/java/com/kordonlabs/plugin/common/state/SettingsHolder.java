package com.kordonlabs.plugin.common.state;

/** The current {@link ServerSettings}: read on any thread, written by the heartbeat on the worker. */
public final class SettingsHolder {

    private volatile ServerSettings current = ServerSettings.DEFAULTS;

    /** Never null. */
    public ServerSettings get() {
        return current;
    }

    public void set(ServerSettings settings) {
        current = settings == null ? ServerSettings.DEFAULTS : settings;
    }
}
