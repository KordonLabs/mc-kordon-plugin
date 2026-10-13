package com.kordonlabs.plugin.common.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Detects Bedrock (Floodgate/Geyser) players. Active only when a plugin with id {@code floodgate} is installed; the
 * plugin descriptors declare it as an optional dependency so it loads first. {@code FloodgateApi.isFloodgatePlayer}
 * is called reflectively through Floodgate's own class loader, so nothing links against Floodgate, and any error
 * counts as "not Bedrock".
 */
public final class FloodgateHook {

    /** No Floodgate on this proxy: nobody is a Bedrock player. */
    public static final FloodgateHook NONE = new FloodgateHook(null);

    static final String API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";
    private static final MethodType CHECK = MethodType.methodType(boolean.class, UUID.class);

    private final ClassLoader loader;
    /** {@code api.isFloodgatePlayer(UUID)} bound to the API instance; null until the API answered once. */
    private volatile MethodHandle check;
    /** The API is not what we expect (renamed or missing): stop trying until restart. */
    private volatile boolean broken;

    private FloodgateHook(ClassLoader loader) {
        this.loader = loader;
    }

    /** The hook for Floodgate's plugin class loader, or {@link #NONE} when it is null. */
    public static FloodgateHook of(ClassLoader floodgateLoader) {
        return floodgateLoader == null ? NONE : new FloodgateHook(floodgateLoader);
    }

    /** Whether Floodgate is installed ({@link #isBedrock} may still be false for everyone). */
    public boolean present() {
        return loader != null;
    }

    /** True for a Floodgate player; false without Floodgate, for a null UUID, or on any error. */
    public boolean isBedrock(UUID uuid) {
        if (loader == null || broken || uuid == null) return false;
        try {
            MethodHandle h = check;
            if (h == null) {
                h = resolve();
                if (h == null) return false; // API instance not ready yet: ask again next time
                check = h;
            }
            return (boolean) h.invokeExact(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    private MethodHandle resolve() {
        try {
            Class<?> api = Class.forName(API_CLASS, true, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            if (instance == null) return null;
            Method isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            return MethodHandles.publicLookup().unreflect(isFloodgatePlayer).bindTo(instance).asType(CHECK);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            broken = true;
            return null;
        }
    }
}
