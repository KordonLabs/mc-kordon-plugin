package com.kordonlabs.plugin.velocity.compat;

import com.kordonlabs.plugin.common.compat.FakeChannels;
import com.velocitypowered.api.proxy.Player;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Netty channel of a Velocity player, read through the proxy's internals by name
 * ({@code getConnection().getChannel()}). When the internals differ, no channel is found and the player counts as real.
 */
public final class VelocityChannels {

    private static final MethodType GETTER = MethodType.methodType(Object.class, Object.class);
    private static final ClassValue<ConcurrentHashMap<String, Optional<MethodHandle>>> GETTERS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Optional<MethodHandle>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private VelocityChannels() {
    }

    public static Object channel(Player player) {
        if (player == null) return null;
        try {
            Object connection = call(player, "getConnection");
            return connection == null ? null : call(connection, "getChannel");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isFake(Player player) {
        return FakeChannels.isFake(channel(player));
    }

    private static Object call(Object target, String name) throws Throwable {
        MethodHandle handle = GETTERS.get(target.getClass()).computeIfAbsent(name,
                n -> Optional.ofNullable(resolve(target.getClass(), n))).orElse(null);
        if (handle == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
        return handle.invokeExact(target);
    }

    private static MethodHandle resolve(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class) return null;
            return MethodHandles.publicLookup().unreflect(m).asType(GETTER);
        } catch (Throwable absent) {
            return null;
        }
    }
}
