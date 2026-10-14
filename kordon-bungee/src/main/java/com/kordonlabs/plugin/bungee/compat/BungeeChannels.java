package com.kordonlabs.plugin.bungee.compat;

import com.kordonlabs.plugin.common.compat.FakeChannels;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Netty channel of a BungeeCord connection, read through the proxy's internals: {@code getCh()} of a player or
 * the {@code ch} field of a pending connection, then the wrapper's {@code getHandle()}. When the internals differ, no
 * channel is found and the connection counts as real.
 */
public final class BungeeChannels {

    private static final ClassValue<Optional<Field>> CH_FIELD = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("ch");
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (Throwable notHere) {
                    // try the superclass
                }
            }
            return Optional.empty();
        }
    };
    private static final ConcurrentHashMap<Class<?>, Boolean> GETTER_ABSENT = new ConcurrentHashMap<>();

    private BungeeChannels() {
    }

    public static Object channel(Object connection) {
        if (connection == null) return null;
        try {
            Object wrapper = null;
            if (!GETTER_ABSENT.containsKey(connection.getClass())) {
                try {
                    wrapper = Reflect.call(connection, "getCh");
                } catch (NoSuchMethodException absent) {
                    GETTER_ABSENT.put(connection.getClass(), Boolean.TRUE);
                }
            }
            if (wrapper == null) {
                Field f = CH_FIELD.get(connection.getClass()).orElse(null);
                if (f != null) wrapper = f.get(connection);
            }
            return wrapper == null ? null : Reflect.call(wrapper, "getHandle");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isFake(Object connection) {
        return FakeChannels.isFake(channel(connection));
    }
}
