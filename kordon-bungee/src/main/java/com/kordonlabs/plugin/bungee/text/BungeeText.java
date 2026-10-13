package com.kordonlabs.plugin.bungee.text;

import com.kordonlabs.plugin.bungee.compat.Reflect;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.LoginEvent;

/**
 * Turns the core's {@code §}-coded legacy text into Bungee chat components. It uses
 * {@code TextComponent.fromLegacy(String)} (1.20+) when present, otherwise
 * {@code new TextComponent(TextComponent.fromLegacyText(s))}, which every version has.
 */
public final class BungeeText {

    private BungeeText() {
    }

    /** One component for {@code legacySection}, keeping {@code \n}; null gives an empty component. */
    public static BaseComponent of(String legacySection) {
        String s = legacySection == null ? "" : legacySection;
        MethodHandle fromLegacy = Reflect.FROM_LEGACY;
        if (fromLegacy != null) {
            try {
                return (BaseComponent) fromLegacy.invoke(s);
            } catch (Throwable t) {
                // fall through to the method every version has
            }
        }
        return legacy(s);
    }

    /**
     * Like {@link #of}, but without the links Bungee's legacy parser adds by itself. A word that looks like an address
     * (such as {@code evil.ru/x} in a client brand or nick) stays plain text instead of becoming a clickable link.
     * Kordon's own links are added explicitly by {@code BungeeSender.sendLink}.
     */
    public static BaseComponent chat(String legacySection) {
        BaseComponent line = of(legacySection);
        unlink(line);
        return line;
    }

    private static void unlink(BaseComponent component) {
        ClickEvent click = component.getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.OPEN_URL) component.setClickEvent(null);
        List<BaseComponent> extra = component.getExtra();
        if (extra != null) {
            for (BaseComponent child : extra) unlink(child);
        }
    }

    /**
     * Blocks the login. It calls {@code setCancelled(true)} <em>first</em> and sets the kick text separately, so a
     * failure to set the text never lets the player in; the proxy then shows its own kick text.
     */
    public static void denyLogin(LoginEvent event, String legacySection) {
        event.setCancelled(true);
        if (legacySection == null) return;
        try {
            BaseComponent reason = of(legacySection);
            MethodHandle setReason = Reflect.LOGIN_SET_REASON;
            if (setReason != null) {
                setReason.invoke(event, reason);
            } else {
                cancelReason(event, reason);
            }
        } catch (Throwable t) {
            // The player stays blocked, with the proxy's default kick text.
        }
    }

    @SuppressWarnings("deprecation") // the only text setter before 1.20
    private static void cancelReason(LoginEvent event, BaseComponent reason) {
        event.setCancelReason(reason);
    }

    @SuppressWarnings("deprecation") // fromLegacyText is deprecated since 1.20, but the only parser on older proxies
    static BaseComponent legacy(String legacySection) {
        return new TextComponent(TextComponent.fromLegacyText(legacySection));
    }
}
