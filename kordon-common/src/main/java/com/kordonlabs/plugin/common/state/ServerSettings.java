package com.kordonlabs.plugin.common.state;

/**
 * What the last successful heartbeat said, held immutably in {@link SettingsHolder}. Until the first heartbeat the
 * plugin uses {@link #DEFAULTS} with failOpen set: no settings are kept on disk, so a server set to block everyone
 * that restarts during a Kordon outage lets players in until the panel answers.
 *
 * @param serverId             null until the first heartbeat
 * @param mode                 "protect" or "monitor"
 * @param failOpen             the panel's "If the check is unavailable" setting: true lets players in
 * @param messages             never null
 * @param settingsVersion      null until the panel sent one
 * @param heartbeatIntervalSec clamped to 15..300
 * @param legacyPanel          the last successful heartbeat had no {@code features} (older panel)
 * @param sync                 null when the panel does not offer sync (older panel, or switched off)
 * @param lists                null when the feature key is absent (the command then fails locally); false when the
 *                             panel's in-game commands are off (the request is still sent and refused with 403)
 * @param lookup               like {@code lists}
 * @param latestVersion        the panel's current plugin version; null when none
 * @param pluginPage           the panel's Plugin page for this server; null until the panel sent it
 * @param fetchedAtMillis      wall-clock time of that heartbeat; 0 for {@link #DEFAULTS}
 */
public record ServerSettings(String serverId, String serverName, String mode, boolean failOpen, Messages messages,
                             String settingsVersion, int heartbeatIntervalSec, boolean legacyPanel, SyncPacing sync,
                             Boolean lists, Boolean lookup, String latestVersion, String pluginPage,
                             long fetchedAtMillis) {

    /** Kick text of a fail-closed login when no panel text is known; matches the panel's default. */
    public static final String BUILTIN_CHECK_FAILED = "Не удалось проверить ваше подключение. Попробуйте зайти через минуту.";
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SEC = 60;

    /** Before any heartbeat: protect mode, failOpen, the panel's default texts, no features. */
    public static final ServerSettings DEFAULTS = new ServerSettings(null, null, "protect", true,
            new Messages("Вход через VPN или прокси на этот сервер запрещён. Отключите VPN и зайдите снова.",
                    "Вы заблокированы на этом сервере.", "Вход на сервер из вашей страны закрыт.", BUILTIN_CHECK_FAILED),
            null, DEFAULT_HEARTBEAT_INTERVAL_SEC, false, null, null, null, null, null, 0L);

    public ServerSettings {
        if (mode == null) mode = "protect";
        if (messages == null) messages = new Messages(null, null, null, null);
        heartbeatIntervalSec = clamp(heartbeatIntervalSec, 15, 300);
    }

    /**
     * Kick texts with {@code &} codes; {NICK} and {IP} may be left for the plugin to fill. {@code checkFailed} falls
     * back to {@link #BUILTIN_CHECK_FAILED}. {@code blockedNotice} is the panel's staff line for a fail-closed login:
     * null when the panel sent none (the built-in line is used) and empty when the notice is off.
     */
    public record Messages(String vpn, String blacklist, String country, String checkFailed, String blockedNotice) {

        public Messages {
            if (checkFailed == null || checkFailed.isBlank()) checkFailed = BUILTIN_CHECK_FAILED;
        }

        /** Without a panel staff notice; the built-in one applies. */
        public Messages(String vpn, String blacklist, String country, String checkFailed) {
            this(vpn, blacklist, country, checkFailed, null);
        }
    }

    /** Sync pacing, clamped to safe ranges. Build it with {@link #of} from the panel's nullable values. */
    public record SyncPacing(int intervalSec, int idleIntervalSec, int snapshotSec, int maxEvents, int maxBodyBytes) {

        public static final SyncPacing DEFAULT = new SyncPacing(5, 30, 120, 500, 524_288);

        public SyncPacing {
            intervalSec = clamp(intervalSec, 2, 60);
            idleIntervalSec = clamp(idleIntervalSec, 5, 300);
            snapshotSec = clamp(snapshotSec, 30, 900);
            maxEvents = clamp(maxEvents, 50, 500);
            maxBodyBytes = clamp(maxBodyBytes, 65_536, 524_288);
        }

        /** Clamped pacing; a missing value keeps {@code base}'s, or {@link #DEFAULT}'s when {@code base} is null. */
        public static SyncPacing of(SyncPacing base, Integer intervalSec, Integer idleIntervalSec, Integer snapshotSec,
                                    Integer maxEvents, Integer maxBodyBytes) {
            SyncPacing b = base == null ? DEFAULT : base;
            return new SyncPacing(
                    intervalSec != null ? intervalSec : b.intervalSec,
                    idleIntervalSec != null ? idleIntervalSec : b.idleIntervalSec,
                    snapshotSec != null ? snapshotSec : b.snapshotSec,
                    maxEvents != null ? maxEvents : b.maxEvents,
                    maxBodyBytes != null ? maxBodyBytes : b.maxBodyBytes);
        }
    }

    public boolean monitor() {
        return "monitor".equals(mode);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
