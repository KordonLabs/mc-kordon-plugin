package com.kordonlabs.plugin.common.platform;

import com.kordonlabs.plugin.common.login.LoginVerdict;
import com.kordonlabs.plugin.common.text.LocalizedLine;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * What the core needs from a proxy or server; one implementation per platform module. Every method must be
 * thread-safe, since the core calls them from its own threads.
 */
public interface Platform {

    /** Such as "bungeecord", "waterfall", "xcord" or "velocity": lower-case {@code [a-z0-9_-]}, at most 32 chars. */
    String platformId();

    /** The proxy's own version string, at most 64 chars. */
    String serverVersion();

    /** This plugin's version as the platform reports it (equal to {@code KordonInfo.version()}). */
    String pluginVersion();

    boolean onlineMode();

    int onlineCount();

    /** {@code plugins/Kordon}, which holds only {@code secret.key}; may not exist until a key is saved. */
    Path dataFolder();

    /** The plugin's own jar, from which {@code kordon-install.json} is read. */
    Path pluginJar();

    /** {@code plugins/}, for the duplicate-jar check. */
    Path pluginsFolder();

    /** The plugin's logger (the proxy prefixes lines with {@code [Kordon]}). */
    Logger logger();

    /** A snapshot of the players online now; safe to iterate on any thread. */
    Collection<OnlinePlayer> onlinePlayers();

    Optional<OnlinePlayer> player(UUID uuid);

    /** Case-insensitive exact name. */
    Optional<OnlinePlayer> player(String name);

    /** Sends a chat line ({@code §} codes) to every online player with {@code permission}; not to the console. */
    void notifyStaff(String permission, String legacySection);

    /**
     * Like {@link #notifyStaff(String, String)}, with each recipient reading the line in their client's language
     * ({@link LocalizedLine#forLocale}). Platforms may override this to build each chat component once per language.
     */
    default void notifyStaff(String permission, LocalizedLine line) {
        if (!line.localized()) {
            notifyStaff(permission, line.fallback());
            return;
        }
        for (OnlinePlayer player : onlinePlayers()) {
            if (player.hasPermission(permission)) player.sendMessage(line.forLocale(player.locale()));
        }
    }

    /** Fires the platform's public kick event; false when another plugin cancelled it. */
    boolean fireRemoteKick(OnlinePlayer player, KickCause cause, String legacySection);

    /**
     * Fires the platform's public kick event and returns the kick screen to show: {@code legacySection} or the text a
     * handler set, or null when another plugin cancelled it. Platforms whose event can change the text override this;
     * the default keeps the text.
     */
    default String fireRemoteKickMessage(OnlinePlayer player, KickCause cause, String legacySection) {
        return fireRemoteKick(player, cause, legacySection) ? legacySection : null;
    }

    /** Fires the platform's public login event; returns the (possibly overridden) verdict. */
    LoginVerdict fireLoginChecked(Object connectionKey, LoginVerdict verdict);
}
