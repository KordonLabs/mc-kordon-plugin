package com.kordonlabs.plugin.velocity.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Turns the core's {@code §}-coded legacy text into Adventure components; the only place that does so on Velocity.
 *
 * <p>It uses only Adventure members available in both Adventure 4.26.1 (Velocity 3.x) and 5.2.0 (Velocity 4.x). Do
 * not use {@code Component.empty()}, {@code toBuilder()}, {@code Component.join}, {@code replaceText}, the
 * {@code Identity}/{@code MessageType} overloads of {@code sendMessage}, {@code ClickEvent.value()/action()} or
 * {@code NamedTextColor.ofExact}: Adventure 5 removed or changed them.
 */
public final class VelocityText {

    /** The core emits {@code §}-codes and BungeeCord's hex form {@code §x§r§r§g§g§b§b}; both hex forms must parse. */
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private VelocityText() {
    }

    /** One component for {@code §}-coded text, keeping line breaks; null gives an empty text. */
    public static Component of(String legacySection) {
        return legacySection == null ? Component.text("") : LEGACY.deserialize(legacySection);
    }

    /**
     * A chat line. URLs are not made clickable, so an address inside player-controlled text (a brand or a nickname)
     * stays plain text; Kordon's own links use {@link #link}.
     */
    public static Component chat(String legacySection) {
        return of(legacySection);
    }

    /** {@code text} that opens {@code url} on click and shows {@code hover}, if not empty, on hover. */
    public static Component link(String text, String url, String hover) {
        Component c = of(text).clickEvent(ClickEvent.openUrl(url));
        return hover == null || hover.isEmpty() ? c : c.hoverEvent(HoverEvent.showText(of(hover)));
    }
}
