package com.kordonlabs.plugin.common.text;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every key of {@code kordon/messages_ru.properties}. The core and the platform modules print text only through
 * {@link Messages} with these keys. Keys built at run time ({@code "reason." + code}) go through {@link #byKey}
 * or {@link Messages#get(String, Object...)}.
 */
public enum MessageKey {
    // Console
    LOG_START("log.start"),
    LOG_NOT_CONFIGURED("log.not_configured"),
    LOG_CONNECTED("log.connected"),
    LOG_SETTINGS_CHANGED("log.settings_changed"),
    MODE_PROTECT("mode.protect"),
    MODE_MONITOR("mode.monitor"),
    FAILOPEN_TRUE("failopen.true"),
    FAILOPEN_FALSE("failopen.false"),
    LOG_BLOCKED("log.blocked"),
    LOG_WOULD_BLOCK("log.would_block"),
    LOG_FAIL_OPEN("log.fail_open"),
    LOG_FAIL_CLOSED("log.fail_closed"),
    CAUSE_TIMEOUT("cause.timeout"),
    CAUSE_NETWORK("cause.network"),
    CAUSE_SERVER_ERROR("cause.server_error"),
    CAUSE_BAD_REQUEST("cause.bad_request"),
    CAUSE_UNAUTHORIZED("cause.unauthorized"),
    CAUSE_UNSUPPORTED("cause.unsupported"),
    LOG_UNAUTHORIZED("log.unauthorized"),
    FAILOPEN_ACTION_TRUE("failopen.action.true"),
    FAILOPEN_ACTION_FALSE("failopen.action.false"),
    LOG_ACCOUNT_DISABLED("log.account_disabled"),
    LOG_LEGACY_PANEL("log.legacy_panel"),
    LOG_FEATURE_OFF("log.feature_off"),
    LOG_SYNC_FAILING("log.sync_failing"),
    LOG_QUEUE_OVERFLOW("log.queue_overflow"),
    LOG_UPDATE_AVAILABLE("log.update_available"),
    LOG_DUPLICATE_JARS("log.duplicate_jars"),
    LOG_REMOTE_KICK("log.remote_kick"),
    KICK_WHY_PANEL("kick.why.panel"),
    KICK_WHY_BLACKLIST("kick.why.blacklist"),
    KICK_WHY_GAME("kick.why.game"),
    LOG_LIST_CHANGED("log.list_changed"),
    LOG_NOT_INET("log.not_inet"),
    LOG_BAD_PANEL_URL("log.bad_panel_url"),
    LOG_PANEL_REDIRECT("log.panel_redirect"),
    LOG_PANEL_HTTP("log.panel_http"),
    LOG_LOGIN_OVERLOAD("log.login_overload"),
    LOG_JAR_REPLACED("log.jar_replaced"),
    LOG_LATE_VERDICT("log.late_verdict"),
    LOG_START_FAILED("log.start_failed"),
    LOG_OVERRIDDEN("log.overridden"),
    CAUSE_RATE_LIMITED("cause.rate_limited"),
    CAUSE_REDIRECT("cause.redirect"),
    CAUSE_TOO_LARGE("cause.too_large"),
    FEATURE_SYNC("feature.sync"),
    FEATURE_LISTS("feature.lists"),
    FEATURE_LOOKUP("feature.lookup"),
    LOG_INSTALL_NEWER("log.install_newer"),
    LOG_LOGIN_REJECTED("log.login_rejected"),
    LOG_LEGACY_CONFIG("log.legacy_config"),
    // Server key, /kordon link, the Kordon API address and connection restored
    LOG_KEY_SAVED("log.key_saved"),
    LOG_KEY_REPLACED("log.key_replaced"),
    LOG_KEY_KEPT("log.key_kept"),
    LOG_KEY_INVALID("log.key_invalid"),
    LOG_KEY_SAVE_FAILED("log.key_save_failed"),
    LOG_PANEL_URL_PROPERTY("log.panel_url_property"),
    LOG_PANEL_URL_PROPERTY_INVALID("log.panel_url_property_invalid"),
    LOG_LINKED("log.linked"),
    LOG_UNLINKED("log.unlinked"),
    LOG_LINK_FAILED("log.link_failed"),
    LOG_CONNECTION_RESTORED("log.connection_restored"),
    LOG_CONNECTION_RESTORED_SHORT("log.connection_restored_short"),
    CAUSE_ACCOUNT_DISABLED("cause.account_disabled"),
    CAUSE_SAVE_FAILED("cause.save_failed"),
    ACTOR_CONSOLE("actor.console"),
    KEY_SOURCE_FILE("key.source.file"),
    KEY_SOURCE_JAR("key.source.jar"),
    KEY_SOURCE_LINK("key.source.link"),

    // Reason labels of /login verdicts ({reasons} in log.blocked, log.would_block, notify.blocked)
    REASON_VPN("reason.vpn"),
    REASON_PROXY("reason.proxy"),
    REASON_RESPROXY("reason.resproxy"),
    REASON_TOR("reason.tor"),
    REASON_HOSTING("reason.hosting"),
    REASON_SUSPICIOUS("reason.suspicious"),
    REASON_COUNTRY("reason.country"),
    REASON_BLACKLIST("reason.blacklist"),
    REASON_WHITELIST("reason.whitelist"),
    REASON_CHECK_FAILED("reason.check_failed"),
    REASON_PRIVATE_IP("reason.private_ip"),
    REASON_KORDON_BASE("reason.kordon_base"),
    REASON_KORDON_BASE_BOT("reason.kordon_base.bot"),
    REASON_KORDON_BASE_FRAUD("reason.kordon_base.fraud"),
    REASON_KORDON_BASE_CHEAT("reason.kordon_base.cheat"),
    REASON_KORDON_BASE_ABUSE("reason.kordon_base.abuse"),
    REASON_MOBILE("reason.mobile"),
    REASON_NICK("reason.nick"),
    REASON_NICK_KORDON("reason.nick.kordon"),
    REASON_VERSION("reason.version"),
    REASON_ATTACK("reason.attack"),
    REASON_ATTACK_GIBBERISH("reason.attack.gibberish"),
    REASON_ATTACK_DIGITS("reason.attack.digits"),
    REASON_ATTACK_PREFIX_COUNTER("reason.attack.prefixcounter"),
    REASON_ATTACK_NEAR_DUPLICATE("reason.attack.nearduplicate"),
    REASON_ATTACK_SAME_SHAPE("reason.attack.sameshape"),
    REASON_ATTACK_PROTOCOL("reason.attack.protocol"),
    REASON_ATTACK_BRAND_SAME("reason.attack.brandsame"),
    REASON_ATTACK_BRAND_MISSING("reason.attack.brandmissing"),
    REASON_REPEAT_BOT("reason.repeat_bot"),
    REASON_MAINTENANCE("reason.maintenance"),
    REASON_PLAN_LIMIT("reason.plan_limit"),
    REASON_OTHER("reason.other"),

    // Chat (&-codes)
    CMD_PREFIX("cmd.prefix"),
    NOTIFY_BLOCKED("notify.blocked"),
    NOTIFY_SUMMARY("notify.summary"),
    CMD_HELP_HEADER("cmd.help.header"),
    CMD_HELP_STATUS("cmd.help.status"),
    CMD_HELP_RELOAD("cmd.help.reload"),
    CMD_HELP_LOOKUP("cmd.help.lookup"),
    CMD_HELP_WHITELIST("cmd.help.whitelist"),
    CMD_HELP_BLACKLIST("cmd.help.blacklist"),
    CMD_USAGE_LIST("cmd.usage.list"),
    CMD_USAGE_LOOKUP("cmd.usage.lookup"),
    CMD_NO_PERMISSION("cmd.no_permission"),
    CMD_PENDING("cmd.pending"),
    CMD_ERR_DISABLED("cmd.err.disabled"),
    CMD_ERR_RATE("cmd.err.rate"),
    CMD_ERR_NETWORK("cmd.err.network"),
    CMD_ERR_UNSUPPORTED("cmd.err.unsupported"),
    CMD_ERR_UNAUTHORIZED("cmd.err.unauthorized"),
    CMD_ERR_ACCOUNT_DISABLED("cmd.err.account_disabled"),
    CMD_ERR_NOT_CONFIGURED("cmd.err.not_configured"),
    CMD_ERR_NO_PLAYER_IP("cmd.err.no_player_ip"),
    CMD_ERR_IP_PERMISSION("cmd.err.ip_permission"),
    CMD_NOTE_TRUNCATED("cmd.note_truncated"),
    CMD_RELOAD_DONE("cmd.reload.done"),
    CMD_RELOAD_JAR_REPLACED("cmd.reload.jar_replaced"),
    CMD_LIST_ALREADY_REMOVED("cmd.list.already_removed"),
    CMD_LINK("cmd.link"),
    CMD_LINK_HOVER("cmd.link.hover"),
    CMD_STATUS_HEADER("cmd.status.header"),
    CMD_STATUS_PANEL_OK("cmd.status.panel_ok"),
    CMD_STATUS_PANEL_LOST("cmd.status.panel_lost"),
    CMD_STATUS_SERVER("cmd.status.server"),
    CMD_STATUS_TOKEN("cmd.status.token"),
    CMD_STATUS_ONLINE("cmd.status.online"),
    CMD_STATUS_UPDATE("cmd.status.update"),
    CMD_STATUS_PANEL_NEVER("cmd.status.panel_never"),
    CMD_STATUS_NOT_CONFIGURED("cmd.status.not_configured"),
    CMD_STATUS_SERVER_UNKNOWN("cmd.status.server_unknown"),
    CMD_STATUS_SESSIONS_ON("cmd.status.sessions_on"),
    CMD_STATUS_SESSIONS_OFF("cmd.status.sessions_off"),
    CMD_STATUS_COMMANDS_ON("cmd.status.commands_on"),
    CMD_STATUS_COMMANDS_OFF("cmd.status.commands_off"),
    CMD_ERR_REJECTED("cmd.err.rejected"),
    CMD_LINK_CONSOLE("cmd.link.console"),
    CMD_RELOAD_FAILED("cmd.reload.failed"),
    CMD_ERR_LOCAL_PLAYER_IP("cmd.err.local_player_ip"),
    // /kordon link and /kordon unlink
    CMD_HELP_LINK("cmd.help.link"),
    CMD_HELP_UNLINK("cmd.help.unlink"),
    CMD_USAGE_LINK("cmd.usage.link"),
    CMD_LINK_KEY_CHAT_WARNING("cmd.link_key.chat_warning"),
    CMD_LINK_KEY_INVALID("cmd.link_key.invalid"),
    CMD_LINK_KEY_CHECKING("cmd.link_key.checking"),
    CMD_LINK_KEY_DONE("cmd.link_key.done"),
    CMD_LINK_KEY_SAME("cmd.link_key.same"),
    CMD_LINK_KEY_REFUSED("cmd.link_key.refused"),
    CMD_LINK_KEY_UNREACHABLE("cmd.link_key.unreachable"),
    CMD_LINK_KEY_ACCOUNT_DISABLED("cmd.link_key.account_disabled"),
    CMD_LINK_KEY_RATE("cmd.link_key.rate"),
    CMD_LINK_KEY_SAVE_FAILED("cmd.link_key.save_failed"),
    CMD_LINK_KEY_BUSY("cmd.link_key.busy"),
    CMD_LINK_KEY_NO_API("cmd.link_key.no_api"),
    CMD_UNLINK_DONE("cmd.unlink.done"),
    CMD_UNLINK_JAR_KEY("cmd.unlink.jar_key"),
    CMD_UNLINK_NONE("cmd.unlink.none"),
    CMD_UNLINK_FAILED("cmd.unlink.failed"),

    // Durations ({ago}, {sec}) and platform names ({platform})
    TIME_SEC("time.sec"),
    TIME_MIN("time.min"),
    TIME_HOUR("time.hour"),
    TIME_DAY("time.day"),
    PLATFORM_BUNGEECORD("platform.bungeecord"),
    PLATFORM_WATERFALL("platform.waterfall"),
    PLATFORM_XCORD("platform.xcord"),
    PLATFORM_TRAVERTINE("platform.travertine"),
    PLATFORM_FLAMECORD("platform.flamecord"),
    PLATFORM_VELOCITY("platform.velocity");

    private static final Map<String, MessageKey> BY_KEY = new HashMap<>();

    static {
        for (MessageKey k : values()) BY_KEY.put(k.key, k);
    }

    private final String key;

    MessageKey(String key) {
        this.key = key;
    }

    /** The property key, e.g. {@code "log.start"}. */
    public String key() {
        return key;
    }

    /** The constant for a property key built at run time, e.g. {@code "reason." + code}. */
    public static Optional<MessageKey> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /** {@code mode.protect} / {@code mode.monitor} for the panel's {@code mode}; unknown modes read as "protect". */
    public static MessageKey mode(String mode) {
        return "monitor".equals(mode) ? MODE_MONITOR : MODE_PROTECT;
    }

    /** The label of the failOpen setting: let everyone in, or let no one in. */
    public static MessageKey failOpen(boolean failOpen) {
        return failOpen ? FAILOPEN_TRUE : FAILOPEN_FALSE;
    }

    /** The action taken under the failOpen setting: letting players in, or not. */
    public static MessageKey failOpenAction(boolean failOpen) {
        return failOpen ? FAILOPEN_ACTION_TRUE : FAILOPEN_ACTION_FALSE;
    }

    @Override
    public String toString() {
        return key;
    }
}
