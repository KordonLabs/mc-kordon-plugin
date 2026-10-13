package com.kordonlabs.plugin.bungee.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection guards for members that differ between proxy versions. Every lookup is by <em>name</em>
 * ({@code Class#getMethod} then {@code MethodHandles.publicLookup().unreflect}), never {@code findVirtual} with a fixed
 * {@code MethodType}, because return types differ between proxy versions ({@code getProperties()} returns
 * {@code protocol.Property[]}, {@code protocol.data.Property[]} or {@code LoginResult$Property[]}).
 *
 * <ul>
 *   <li><b>API probes</b> ({@link #CLIENT_BRAND}, {@link #FROM_LEGACY}, {@link #LOGIN_SET_REASON}) are resolved once
 *       against the plugin's class loader, which sees the proxy's API; null means absent on this proxy.</li>
 *   <li><b>Runtime getters</b> ({@link #getter}, {@link #call}) are public no-argument methods of proxy-internal
 *       classes (InitialHandler, LoginResult, Property) known only at run time. They are resolved lazily and cached
 *       per runtime class in a {@link ClassValue}, so nothing pins the plugin's class loader.</li>
 * </ul>
 *
 * <p>No class outside this package references the guarded members directly.
 */
public final class Reflect {

    static final String PROXIED_PLAYER = "net.md_5.bungee.api.connection.ProxiedPlayer";
    static final String TEXT_COMPONENT = "net.md_5.bungee.api.chat.TextComponent";
    static final String BASE_COMPONENT = "net.md_5.bungee.api.chat.BaseComponent";
    static final String LOGIN_EVENT = "net.md_5.bungee.api.event.LoginEvent";

    /**
     * The API-level probes of one class loader; a null handle means the member is absent there.
     *
     * @param clientBrand    {@code ProxiedPlayer#getClientBrand()}: {@code (ProxiedPlayer)String} (1.21-R0.4, XCord)
     * @param fromLegacy     {@code static TextComponent.fromLegacy(String)}: {@code (String)BaseComponent} (1.20+)
     * @param loginSetReason {@code LoginEvent#setReason(BaseComponent)}: {@code (LoginEvent,BaseComponent)void} (1.20+)
     */
    public record Probes(MethodHandle clientBrand, MethodHandle fromLegacy, MethodHandle loginSetReason) {
    }

    private static final Probes PROBES = probe(Reflect.class.getClassLoader());

    /** {@code ProxiedPlayer#getClientBrand()}; null on older proxies, which then use {@code BrandListener}. */
    public static final MethodHandle CLIENT_BRAND = PROBES.clientBrand();
    /** {@code TextComponent.fromLegacy(String)}; null before 1.20, where {@code fromLegacyText} is used instead. */
    public static final MethodHandle FROM_LEGACY = PROBES.fromLegacy();
    /** {@code LoginEvent#setReason(BaseComponent)}; null before 1.20, where {@code setCancelReason} is used instead. */
    public static final MethodHandle LOGIN_SET_REASON = PROBES.loginSetReason();

    private static final MethodType GETTER = MethodType.methodType(Object.class, Object.class);

    /** Per runtime class: method name to its {@code (Object)Object} getter handle; empty when absent. */
    private static final ClassValue<ConcurrentHashMap<String, Optional<MethodHandle>>> GETTERS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Optional<MethodHandle>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private Reflect() {
    }

    /** The probes of the running proxy, resolved when this class is first used. */
    public static Probes probe() {
        return PROBES;
    }

    /** Resolves the API probes against {@code apiLoader}. Never throws: anything missing is null. */
    public static Probes probe(ClassLoader apiLoader) {
        Class<?> baseComponent = type(apiLoader, BASE_COMPONENT);
        return new Probes(
                method(apiLoader, PROXIED_PLAYER, "getClientBrand", false),
                method(apiLoader, TEXT_COMPONENT, "fromLegacy", true, String.class),
                baseComponent == null ? null : method(apiLoader, LOGIN_EVENT, "setReason", false, baseComponent));
    }

    /**
     * The public no-argument method {@code name} of {@code type} as {@code (Object)Object}; null when there is none
     * (or it is not publicly accessible). Cached per class.
     */
    public static MethodHandle getter(Class<?> type, String name) {
        return GETTERS.get(type).computeIfAbsent(name, n -> Optional.ofNullable(resolveGetter(type, n))).orElse(null);
    }

    /** Calls {@code target.name()}; throws {@link NoSuchMethodException} when the runtime class has no such getter. */
    public static Object call(Object target, String name) throws Throwable {
        MethodHandle handle = getter(target.getClass(), name);
        if (handle == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
        return handle.invokeExact(target);
    }

    private static Class<?> type(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static MethodHandle method(ClassLoader loader, String owner, String name, boolean isStatic,
                                       Class<?>... parameters) {
        try {
            Class<?> type = type(loader, owner);
            if (type == null) return null;
            Method m = type.getMethod(name, parameters);
            if (Modifier.isStatic(m.getModifiers()) != isStatic) return null;
            return MethodHandles.publicLookup().unreflect(m);
        } catch (Throwable absent) {
            return null; // NoSuchMethodException, a LinkageError from a broken signature, IllegalAccessException, etc.
        }
    }

    private static MethodHandle resolveGetter(Class<?> type, String name) {
        Method found;
        try {
            found = type.getMethod(name);
        } catch (Throwable absent) {
            return null;
        }
        if (Modifier.isStatic(found.getModifiers()) || found.getReturnType() == void.class) return null;
        MethodHandle handle = unreflectGetter(found);
        if (handle != null) return handle;
        // Declared in a non-public class: use the same method from a public supertype that declares it. Virtual
        // dispatch still reaches the runtime class's implementation.
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> c = pending.poll();
            if (Modifier.isPublic(c.getModifiers())) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                        handle = unreflectGetter(m);
                        if (handle != null) return handle;
                    }
                } catch (Throwable notHere) {
                    // keep looking up the hierarchy
                }
            }
            if (c.getSuperclass() != null) pending.add(c.getSuperclass());
            for (Class<?> i : c.getInterfaces()) pending.add(i);
        }
        return null;
    }

    private static MethodHandle unreflectGetter(Method m) {
        try {
            return MethodHandles.publicLookup().unreflect(m).asType(GETTER);
        } catch (Throwable inaccessible) {
            return null;
        }
    }
}
