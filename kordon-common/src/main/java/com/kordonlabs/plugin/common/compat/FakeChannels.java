package com.kordonlabs.plugin.common.compat;

/**
 * Detects connections that only exist inside the proxy: NPC and bot plugins log players in over in-process Netty
 * channels. Such players are neither checked nor tracked. Based on PacketEvents' FakeChannelUtil.
 */
public final class FakeChannels {

    private FakeChannels() {
    }

    public static boolean isFake(Object channel) {
        if (channel == null) return false;
        String name = channel.getClass().getSimpleName();
        return name.equals("FakeChannel") || name.equals("SpoofedChannel") || name.equals("EmbeddedChannel");
    }
}
