package com.kordonlabs.plugin.bungee.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Makes {@code completeIntent} safe to call from another thread. Bungee's {@code AsyncEvent} (1.16-R0.4 to 1.21-R0.4,
 * BungeeCord and Waterfall share the same bytecode) has a race: {@code postCall()} reads {@code latch} and only then
 * sets {@code fired}, while {@code completeIntent()} reads {@code fired} and only then decrements {@code latch}. When
 * the two overlap, {@code postCall} still counts the intent and {@code completeIntent} still thinks the event is being
 * dispatched, so nobody runs the callback and the login hangs until the client times out. Kordon's fast verdicts
 * (cached, account disabled) are released microseconds after {@code registerIntent}, right when the proxy's thread
 * calls {@code postCall()}.
 *
 * <p>{@link #awaitPostCall} lets the releasing thread wait until {@code fired} is set. From then on {@code postCall}
 * has skipped the callback and {@code completeIntent} runs it exactly once. The private field is read by name (the
 * proxy uses unnamed modules, so no {@code --add-opens} is needed); on a fork without it the intent completes at once.
 */
public final class AsyncEvents {

    static final String ASYNC_EVENT = "net.md_5.bungee.api.event.AsyncEvent";
    private static final MethodType GETTER = MethodType.methodType(Object.class, Object.class);
    /** Busy-wait first: the proxy's thread is normally a few handlers away from {@code postCall()}. */
    private static final int SPINS = 10_000;
    private static final long PARK_NANOS = 100_000;

    /** {@code AsyncEvent.fired} ({@code private final AtomicBoolean}) as {@code (Object)Object}; null when absent. */
    private static final MethodHandle FIRED = firedGetter(AsyncEvents.class.getClassLoader());

    private AsyncEvents() {
    }

    /** The getter of {@code AsyncEvent.fired} as seen by {@code apiLoader}; null when a fork changed the field. */
    static MethodHandle firedGetter(ClassLoader apiLoader) {
        try {
            Class<?> type = Class.forName(ASYNC_EVENT, false, apiLoader);
            Field fired = type.getDeclaredField("fired");
            if (Modifier.isStatic(fired.getModifiers()) || fired.getType() != AtomicBoolean.class) return null;
            if (!fired.trySetAccessible()) return null;
            return MethodHandles.lookup().unreflectGetter(fired).asType(GETTER);
        } catch (Throwable absent) {
            return null;
        }
    }

    /** Whether the getter resolved against the plugin's own class loader, which sees the running proxy's API. */
    static boolean supported() {
        return FIRED != null;
    }

    /**
     * Waits (spin, then 0.1 ms parks) until the proxy has called {@code postCall()} on {@code event}, at most
     * {@code maxNanos}. Returns at once when it already has, or when that cannot be known. Call it outside any lock
     * the proxy's thread could need, and never on the thread that dispatches the event (it would wait for itself).
     *
     * @return true when {@code postCall()} has run
     */
    public static boolean awaitPostCall(Object event, long maxNanos) {
        if (FIRED == null || event == null) return false;
        AtomicBoolean fired;
        try {
            if (!(FIRED.invokeExact(event) instanceof AtomicBoolean flag)) return false;
            fired = flag;
        } catch (Throwable t) {
            return false;
        }
        long start = System.nanoTime();
        for (int round = 0; !fired.get(); round++) {
            if (System.nanoTime() - start >= maxNanos) return false;
            if (round < SPINS) Thread.onSpinWait();
            else LockSupport.parkNanos(PARK_NANOS);
        }
        return true;
    }
}
