package com.kordonlabs.plugin.common.install;

import com.kordonlabs.plugin.common.text.MessageKey;

/** Where the server key in use comes from, as shown in the start line and {@code /kordon status}. */
public enum KeySource {
    /** {@code plugins/Kordon/secret.key}. */
    FILE(MessageKey.KEY_SOURCE_FILE),
    /** Built into the personal Kordon.jar; saved to {@code secret.key} at start. */
    JAR(MessageKey.KEY_SOURCE_JAR),
    /** Set with {@code /kordon link <key>} during this run and saved to {@code secret.key}. */
    LINK(MessageKey.KEY_SOURCE_LINK),
    /** No key; the plugin is NOT_CONFIGURED. */
    NONE(null);

    private final MessageKey label;

    KeySource(MessageKey label) {
        this.label = label;
    }

    /** The text naming this source; null for {@link #NONE}. */
    public MessageKey label() {
        return label;
    }
}
