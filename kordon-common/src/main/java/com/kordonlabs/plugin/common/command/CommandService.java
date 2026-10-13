package com.kordonlabs.plugin.common.command;

import com.kordonlabs.plugin.common.KordonCore;
import com.kordonlabs.plugin.common.KordonInfo;
import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.Actor;
import com.kordonlabs.plugin.common.api.dto.ListsRequest;
import com.kordonlabs.plugin.common.api.dto.ListsResponse;
import com.kordonlabs.plugin.common.api.dto.LookupRequest;
import com.kordonlabs.plugin.common.api.dto.LookupResponse;
import com.kordonlabs.plugin.common.install.ApiBase;
import com.kordonlabs.plugin.common.install.Credentials;
import com.kordonlabs.plugin.common.install.KeySource;
import com.kordonlabs.plugin.common.install.Tokens;
import com.kordonlabs.plugin.common.kick.KickExecutor;
import com.kordonlabs.plugin.common.platform.OnlinePlayer;
import com.kordonlabs.plugin.common.platform.Permissions;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.platform.Sender;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.state.ConnectionState;
import com.kordonlabs.plugin.common.state.ConnectionStateHolder;
import com.kordonlabs.plugin.common.state.ServerSettings;
import com.kordonlabs.plugin.common.state.SettingsHolder;
import com.kordonlabs.plugin.common.text.Legacy;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.SemVer;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code /kordon} command.
 *
 * <p>Parses on the caller's thread and returns at once. HTTP runs on the background pool and the reply is sent when
 * it completes; disk work for {@code reload}, {@code link} and {@code unlink} runs on the worker. Every path replies:
 * a "pending" notice after one second without an answer, then the answer or an error.
 *
 * <p>A feature key absent from the last heartbeat means an older panel, and the command fails locally without a
 * request. A feature that is present but switched off still sends the request, so the panel's fresh refusal is shown
 * and switching the feature back on works at once.
 *
 * <p>{@code /kordon link <key>} checks the key against the Kordon API before saving it ({@link Linking}). Players are
 * warned that chat and command loggers can record it. The key is never logged in full, echoed or tab-completed.
 */
public final class CommandService {

    /** How long to wait for an answer before telling the sender it is pending. */
    static final Duration PENDING_AFTER = Duration.ofSeconds(1);
    /**
     * {@code /kordon status} reports the panel as connected while the last successful heartbeat is at most two
     * intervals plus this old.
     */
    static final Duration HEARTBEAT_SLACK = Duration.ofSeconds(10);

    private final Platform platform;
    private final KordonApi api;
    private final SettingsHolder settings;
    private final ConnectionStateHolder state;
    private final Supplier<Credentials> credentials;
    private final SessionTracker sessions;
    private final KickExecutor kicks;
    private final Messages messages;
    private final ThrottledLog log;
    private final Scheduler worker;
    private final Clock clock;
    private final Supplier<String> instance;
    private final Supplier<CompletableFuture<KordonCore.ReloadResult>> reload;
    private final Linking linking;
    private final TabCompleter tabs;

    /**
     * @param credentials the Kordon API address and key in use now ({@code /kordon link} replaces them)
     * @param instance    the current instance UUID (changes after {@code /kordon link})
     * @param reload      {@code KordonCore.reload()}, completing on the worker thread
     * @param linking     handles {@code /kordon link} and {@code unlink}; null when not available
     */
    public CommandService(Platform platform, KordonApi api, SettingsHolder settings, ConnectionStateHolder state,
                          Supplier<Credentials> credentials, SessionTracker sessions, KickExecutor kicks,
                          Messages messages, ThrottledLog log, Scheduler worker, Clock clock, Supplier<String> instance,
                          Supplier<CompletableFuture<KordonCore.ReloadResult>> reload, Linking linking) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.api = Objects.requireNonNull(api, "api");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = Objects.requireNonNull(state, "state");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.kicks = Objects.requireNonNull(kicks, "kicks");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.log = Objects.requireNonNull(log, "log");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instance = instance == null ? () -> null : instance;
        this.reload = reload;
        this.linking = linking;
        this.tabs = new TabCompleter(platform);
    }

    /** Returns immediately; the reply is sent asynchronously when HTTP is involved. Never throws. */
    public void execute(Sender sender, String[] args) {
        if (sender == null) return;
        try {
            dispatch(sender, tokens(args));
        } catch (RuntimeException e) {
            log.warn("command.failed", "Kordon: /kordon failed: " + e);
            say(sender, MessageKey.CMD_ERR_NETWORK);
        }
    }

    /** Tab completion; no I/O. */
    public List<String> complete(Sender sender, String[] args) {
        if (sender == null) return List.of();
        try {
            return tabs.complete(sender, args);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ── dispatch ─────────────────────────────────────────────────────────────────────────────────────────────────

    private void dispatch(Sender sender, String[] args) {
        ArgParser.Sub sub = args.length == 0 ? ArgParser.Sub.HELP : ArgParser.sub(args[0]);
        if (sub == null) sub = ArgParser.Sub.HELP;
        switch (sub) {
            case HELP -> {
                if (allowed(sender, Permissions.COMMAND)) help(sender);
            }
            case STATUS -> {
                if (allowed(sender, Permissions.COMMAND)) status(sender);
            }
            case RELOAD -> {
                if (allowed(sender, Permissions.RELOAD)) reload(sender);
            }
            case LOOKUP -> {
                if (allowed(sender, Permissions.LOOKUP)) lookup(sender, args);
            }
            case WHITELIST -> {
                if (allowed(sender, Permissions.WHITELIST)) lists(sender, ListsRequest.WHITELIST, args);
            }
            case BLACKLIST -> {
                if (allowed(sender, Permissions.BLACKLIST)) lists(sender, ListsRequest.BLACKLIST, args);
            }
            case LINK -> {
                if (allowed(sender, Permissions.LINK)) link(sender, args);
            }
            case UNLINK -> {
                if (allowed(sender, Permissions.LINK)) unlink(sender);
            }
        }
    }

    private boolean allowed(Sender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        say(sender, MessageKey.CMD_NO_PERMISSION, "perm", permission);
        return false;
    }

    /** Only the lines the sender may use. */
    private void help(Sender sender) {
        send(sender, messages.get(MessageKey.CMD_HELP_HEADER, "version", KordonInfo.version()));
        send(sender, messages.get(MessageKey.CMD_HELP_STATUS));
        if (sender.hasPermission(Permissions.RELOAD)) send(sender, messages.get(MessageKey.CMD_HELP_RELOAD));
        if (sender.hasPermission(Permissions.LOOKUP)) send(sender, messages.get(MessageKey.CMD_HELP_LOOKUP));
        if (sender.hasPermission(Permissions.WHITELIST)) send(sender, messages.get(MessageKey.CMD_HELP_WHITELIST));
        if (sender.hasPermission(Permissions.BLACKLIST)) send(sender, messages.get(MessageKey.CMD_HELP_BLACKLIST));
        if (sender.hasPermission(Permissions.LINK)) {
            send(sender, messages.get(MessageKey.CMD_HELP_LINK));
            send(sender, messages.get(MessageKey.CMD_HELP_UNLINK));
        }
    }

    // ── status ───────────────────────────────────────────────────────────────────────────────────────────────────

    private void status(Sender sender) {
        ServerSettings s = settings.get();
        Credentials c = credentials.get();
        ConnectionState link = state.get();
        send(sender, messages.get(MessageKey.CMD_STATUS_HEADER, "version", KordonInfo.version(),
                "platform", messages.getOr("platform." + platform.platformId(), platform.platformId()),
                "serverVersion", platform.serverVersion(), "java", KordonInfo.javaVersion()));

        if (!c.configured() || link == ConnectionState.NOT_CONFIGURED) {
            send(sender, messages.get(MessageKey.CMD_STATUS_NOT_CONFIGURED, "panelUrl", ApiBase.pagesUrl(c.apiUrl())));
        } else {
            long lastOk = state.lastOkAtMillis();
            long age = clock.millis() - lastOk;
            String ago = lastOk == 0 ? null : ago(age);
            // The state stays OK through network errors, 5xx and 429, so judge by age: two missed answers mean lost.
            boolean recent = age <= 2L * s.heartbeatIntervalSec() * 1000 + HEARTBEAT_SLACK.toMillis();
            if (lastOk == 0) {
                send(sender, messages.get(MessageKey.CMD_STATUS_PANEL_NEVER, "apiUrl", c.apiUrl()));
            } else if ((link == ConnectionState.OK || link == ConnectionState.LEGACY_PANEL) && recent) {
                send(sender, messages.get(MessageKey.CMD_STATUS_PANEL_OK, "apiUrl", c.apiUrl(), "ago", ago));
            } else {
                send(sender, messages.get(MessageKey.CMD_STATUS_PANEL_LOST, "apiUrl", c.apiUrl(), "ago", ago));
            }
        }

        if (s.serverName() == null && s.serverId() == null) {
            send(sender, messages.get(MessageKey.CMD_STATUS_SERVER_UNKNOWN));
        } else {
            send(sender, messages.get(MessageKey.CMD_STATUS_SERVER,
                    "server", s.serverName() != null ? s.serverName() : s.serverId(),
                    "mode", messages.get(MessageKey.mode(s.mode())),
                    "failOpen", messages.get(MessageKey.failOpen(s.failOpen()))));
        }

        if (c.token() != null) {
            KeySource source = c.source() == null ? KeySource.NONE : c.source();
            String where = source.label() == null ? "" : messages.get(source.label());
            send(sender, messages.get(MessageKey.CMD_STATUS_TOKEN, "tokenPrefix", c.tokenPrefix(), "source", where));
        }

        send(sender, messages.get(MessageKey.CMD_STATUS_ONLINE,
                "online", platform.onlineCount(),
                "queue", sessions.queued(),
                "sessions", messages.get(s.sync() != null
                        ? MessageKey.CMD_STATUS_SESSIONS_ON : MessageKey.CMD_STATUS_SESSIONS_OFF),
                "commands", messages.get(Boolean.TRUE.equals(s.lists())
                        ? MessageKey.CMD_STATUS_COMMANDS_ON : MessageKey.CMD_STATUS_COMMANDS_OFF)));

        if (s.latestVersion() != null && SemVer.isOlder(KordonInfo.version(), s.latestVersion())) {
            send(sender, messages.get(MessageKey.CMD_STATUS_UPDATE, "latest", s.latestVersion()));
        }
    }

    /** A short localized age such as "12 s" or "5 min". */
    String ago(long millis) {
        long sec = Math.max(0, millis) / 1000;
        if (sec < 60) return messages.get(MessageKey.TIME_SEC, "n", sec);
        if (sec < 3600) return messages.get(MessageKey.TIME_MIN, "n", sec / 60);
        if (sec < 86_400) return messages.get(MessageKey.TIME_HOUR, "n", sec / 3600);
        return messages.get(MessageKey.TIME_DAY, "n", sec / 86_400);
    }

    // ── reload ───────────────────────────────────────────────────────────────────────────────────────────────────

    private void reload(Sender sender) {
        Reply reply = new Reply(sender);
        // The command thread may be a proxy network thread, so the disk and zip I/O of a reload runs on the worker.
        worker.execute(() -> {
            CompletableFuture<KordonCore.ReloadResult> future;
            try {
                future = reload == null ? null : reload.get();
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            if (future == null) future = CompletableFuture.failedFuture(new IllegalStateException("Kordon is not running"));
            future.whenComplete((result, error) -> reply.answer(() -> {
                if (error != null || result == null) {
                    say(sender, MessageKey.CMD_RELOAD_FAILED, "error", describe(error));
                } else if (result.jarReplaced()) {
                    say(sender, MessageKey.CMD_RELOAD_JAR_REPLACED);
                } else if (!result.configured()) {
                    say(sender, MessageKey.CMD_ERR_NOT_CONFIGURED);
                } else {
                    say(sender, MessageKey.CMD_RELOAD_DONE);
                }
            }));
        });
    }

    private static String describe(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t == null) return "?";
        if (t instanceof TimeoutException) return "timeout";
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ── link / unlink ────────────────────────────────────────────────────────────────────────────────────────────

    /** Never echoes what was typed; replies with the key's prefix at most. */
    private void link(Sender sender, String[] args) {
        if (args.length != 2) {
            say(sender, MessageKey.CMD_USAGE_LINK);
            return;
        }
        if (!sender.console()) say(sender, MessageKey.CMD_LINK_KEY_CHAT_WARNING);
        String key = ArgParser.cleanKey(args[1]);
        if (!Tokens.valid(key)) {
            say(sender, MessageKey.CMD_LINK_KEY_INVALID);
            return;
        }
        if (linking == null) {
            say(sender, MessageKey.CMD_ERR_NETWORK);
            return;
        }
        String prefix = Tokens.prefix(key);
        say(sender, MessageKey.CMD_LINK_KEY_CHECKING, "tokenPrefix", prefix, "apiUrl", credentials.get().apiUrl());
        CompletableFuture<Linking.LinkResult> future;
        try {
            future = linking.link(key, actorLabel(sender));
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        if (future == null) future = CompletableFuture.completedFuture(Linking.LinkResult.of(Linking.Outcome.NOT_RUNNING));
        future.whenComplete((result, error) -> {
            try {
                linked(sender, prefix, error != null || result == null
                        ? Linking.LinkResult.of(Linking.Outcome.NOT_RUNNING) : result);
            } catch (RuntimeException e) {
                log.warn("command.failed", "Kordon: /kordon link answer failed: " + e);
                say(sender, MessageKey.CMD_ERR_NETWORK);
            }
        });
    }

    private void linked(Sender sender, String prefix, Linking.LinkResult r) {
        String tokenPrefix = r.tokenPrefix() != null ? r.tokenPrefix() : prefix;
        switch (r.outcome()) {
            case LINKED -> say(sender, MessageKey.CMD_LINK_KEY_DONE,
                    "server", Messages.literal(r.serverName() == null ? "" : r.serverName()), "tokenPrefix", tokenPrefix);
            case SAME_KEY -> say(sender, MessageKey.CMD_LINK_KEY_SAME);
            case INVALID -> say(sender, MessageKey.CMD_LINK_KEY_INVALID);
            case REFUSED -> say(sender, MessageKey.CMD_LINK_KEY_REFUSED, "tokenPrefix", tokenPrefix);
            case ACCOUNT_DISABLED -> say(sender, MessageKey.CMD_LINK_KEY_ACCOUNT_DISABLED);
            case RATE_LIMITED -> say(sender, MessageKey.CMD_LINK_KEY_RATE, "sec", Math.max(1, r.retryAfterSec()));
            case UNREACHABLE -> say(sender, MessageKey.CMD_LINK_KEY_UNREACHABLE,
                    "apiUrl", r.apiUrl() != null ? r.apiUrl() : credentials.get().apiUrl(),
                    "cause", messages.getOr("cause." + (r.cause() == null ? "network" : r.cause()),
                            messages.get(MessageKey.CAUSE_NETWORK)));
            case SAVE_FAILED -> say(sender, MessageKey.CMD_LINK_KEY_SAVE_FAILED,
                    "error", Messages.literal(r.error() == null ? "?" : r.error()));
            case NO_API -> say(sender, MessageKey.CMD_LINK_KEY_NO_API);
            case BUSY -> say(sender, MessageKey.CMD_LINK_KEY_BUSY);
            case NOT_RUNNING -> say(sender, MessageKey.CMD_ERR_NETWORK);
        }
    }

    /** Deletes {@code secret.key}; players join unchecked until the next link. */
    private void unlink(Sender sender) {
        if (linking == null) {
            say(sender, MessageKey.CMD_ERR_NETWORK);
            return;
        }
        CompletableFuture<Linking.UnlinkResult> future;
        try {
            future = linking.unlink(actorLabel(sender));
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            future = CompletableFuture.completedFuture(Linking.UnlinkResult.of(Linking.UnlinkOutcome.NOT_RUNNING));
        }
        future.whenComplete((result, error) -> {
            try {
                Linking.UnlinkOutcome outcome = error != null || result == null
                        ? Linking.UnlinkOutcome.NOT_RUNNING : result.outcome();
                switch (outcome) {
                    case UNLINKED -> {
                        say(sender, MessageKey.CMD_UNLINK_DONE);
                        if (result.embeddedPrefix() != null) {
                            say(sender, MessageKey.CMD_UNLINK_JAR_KEY, "tokenPrefix", result.embeddedPrefix());
                        }
                    }
                    case NOT_LINKED -> say(sender, MessageKey.CMD_UNLINK_NONE);
                    case FAILED -> say(sender, MessageKey.CMD_UNLINK_FAILED,
                            "error", Messages.literal(result.error() == null ? "?" : result.error()));
                    case BUSY -> say(sender, MessageKey.CMD_LINK_KEY_BUSY);
                    case NOT_RUNNING -> say(sender, MessageKey.CMD_ERR_NETWORK);
                }
            } catch (RuntimeException e) {
                log.warn("command.failed", "Kordon: /kordon unlink answer failed: " + e);
                say(sender, MessageKey.CMD_ERR_NETWORK);
            }
        });
    }

    /** Who ran the command, for the console log: "console" or the player's name as plain text. */
    private String actorLabel(Sender sender) {
        if (sender.console()) return messages.english().get(MessageKey.ACTOR_CONSOLE);
        return Legacy.plain(sender.name() == null ? "?" : sender.name());
    }

    // ── lookup ───────────────────────────────────────────────────────────────────────────────────────────────────

    private void lookup(Sender sender, String[] args) {
        if (args.length < 2 || args[1].length() > ArgParser.MAX_VALUE) {
            say(sender, MessageKey.CMD_USAGE_LOOKUP);
            return;
        }
        String query = args[1];
        if (!ready(sender, settings.get().lookup())) return;
        boolean showIp = sender.hasPermission(Permissions.LOOKUP_IP);
        if (!showIp && ArgParser.isIp(query)) {
            say(sender, MessageKey.CMD_ERR_IP_PERMISSION);
            return;
        }
        LookupRequest request = new LookupRequest(query, showIp, actor(sender));
        call(sender, () -> api.lookup(request), r -> {
            LookupResponse body = r.body();
            for (String line : body.lines()) sendPanelLine(sender, line);
            String url = body.url();
            if (url != null && !url.isBlank()) {
                if (sender.console()) send(sender, messages.get(MessageKey.CMD_LINK_CONSOLE, "url", url));
                else sender.sendLink(messages.chat(MessageKey.CMD_LINK), url, messages.chat(MessageKey.CMD_LINK_HOVER));
            }
        });
    }

    // ── whitelist / blacklist ────────────────────────────────────────────────────────────────────────────────────

    private void lists(Sender sender, String list, String[] args) {
        ArgParser.ListArgs parsed = ArgParser.parseList(list, args, 1);
        if (parsed == null) {
            say(sender, MessageKey.CMD_USAGE_LIST, "list", list);
            return;
        }
        if (!ready(sender, settings.get().lists())) return;
        String value = parsed.value();
        if (ArgParser.KIND_IP.equals(parsed.kind()) && !ArgParser.isIpOrCidr(value)) {
            // A name with --ip targets the current IP of the online player with that name.
            Optional<OnlinePlayer> online = platform.player(value);
            String ip = online.map(OnlinePlayer::ip).orElse(null);
            if (ip == null) {
                say(sender, MessageKey.CMD_ERR_NO_PLAYER_IP, "name", Messages.literal(value));
                return;
            }
            if (ArgParser.isLocalNetworkIp(ip)) {
                // Behind a tunnel or relay without PROXY protocol every player has this address: an entry for it
                // would kick (blacklist) or wave through (whitelist) everyone. The panel refuses it as well.
                say(sender, MessageKey.CMD_ERR_LOCAL_PLAYER_IP, "name", Messages.literal(value), "ip", ip);
                return;
            }
            value = ip;
        }
        if (parsed.noteTruncated()) say(sender, MessageKey.CMD_NOTE_TRUNCATED);
        ListsRequest request = new ListsRequest(parsed.op(), list, value, parsed.kind(), parsed.expiry(), parsed.note(),
                actor(sender), instance.get());
        call(sender, () -> api.lists(request), r -> {
            ListsResponse body = r.body();
            if (!body.kicks().isEmpty()) kicks.submit(body.kicks());
            boolean retriedRemove = ListsRequest.REMOVE.equals(request.op())
                    && ListsResponse.NOT_FOUND.equals(body.result()) && r.retried();
            if (retriedRemove) {
                // The first attempt probably removed it before the connection broke.
                send(sender, messages.get(MessageKey.CMD_LIST_ALREADY_REMOVED, "value",
                        Messages.literal(request.value())));
            } else {
                for (String line : body.lines()) sendPanelLine(sender, line);
            }
            boolean changed = ListsResponse.ADDED.equals(body.result()) || ListsResponse.UPDATED.equals(body.result())
                    || ListsResponse.REMOVED.equals(body.result());
            if (changed && !body.lines().isEmpty()) {
                log.info(messages.console(MessageKey.LOG_LIST_CHANGED, "actor", Messages.literal(sender.name()),
                        "line", Legacy.plain(body.lines().get(0))));
            }
        });
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────────────────────────────────────────

    /** Whether the plugin is configured and the feature flag is known from a heartbeat ({@code flag} non-null). */
    private boolean ready(Sender sender, Boolean flag) {
        if (!credentials.get().configured() || state.get() == ConnectionState.NOT_CONFIGURED) {
            say(sender, MessageKey.CMD_ERR_NOT_CONFIGURED);
            return false;
        }
        if (flag == null) {
            // No successful heartbeat yet: report what the heartbeat learnt instead (a refused key, a disabled
            // account) or no answer. A panel that answered successfully without the key is an older panel.
            say(sender, state.lastOkAtMillis() != 0 ? MessageKey.CMD_ERR_UNSUPPORTED : switch (state.get()) {
                case UNAUTHORIZED -> MessageKey.CMD_ERR_UNAUTHORIZED;
                case DISABLED -> MessageKey.CMD_ERR_ACCOUNT_DISABLED;
                default -> MessageKey.CMD_ERR_NETWORK;
            });
            return false;
        }
        return true;
    }

    /** Sends the request; the answer, or a pending notice after one second, goes to {@code sender}. */
    private <T> void call(Sender sender, Supplier<CompletableFuture<ApiResult<T>>> request, Consumer<ApiResult<T>> ok) {
        Reply reply = new Reply(sender);
        CompletableFuture<ApiResult<T>> future;
        try {
            future = request.get();
        } catch (RuntimeException e) {
            future = CompletableFuture.completedFuture(ApiResult.noAnswer(ApiResult.Outcome.NETWORK));
        }
        if (future == null) future = CompletableFuture.completedFuture(ApiResult.noAnswer(ApiResult.Outcome.NETWORK));
        future.whenComplete((result, error) -> reply.answer(() -> {
            if (error != null || result == null) {
                say(sender, MessageKey.CMD_ERR_NETWORK);
            } else if (result.ok() && result.body() != null) {
                ok.accept(result);
            } else {
                failure(sender, result);
            }
        }));
    }

    private void failure(Sender sender, ApiResult<?> result) {
        switch (result.outcome()) {
            case INVALID_VALUE -> {
                String message = result.errorMessage();
                if (message == null || message.isBlank()) say(sender, MessageKey.CMD_ERR_REJECTED);
                else send(sender, messages.get(MessageKey.CMD_PREFIX) + "&c" + Legacy.plain(message));
            }
            case RATE_LIMITED -> say(sender, MessageKey.CMD_ERR_RATE, "sec", Math.max(1, result.retryAfterSec()));
            default -> say(sender, errorKey(result.outcome()));
        }
    }

    static MessageKey errorKey(ApiResult.Outcome outcome) {
        return switch (outcome) {
            case UNAUTHORIZED -> MessageKey.CMD_ERR_UNAUTHORIZED;
            case ACCOUNT_DISABLED -> MessageKey.CMD_ERR_ACCOUNT_DISABLED;
            case COMMANDS_DISABLED -> MessageKey.CMD_ERR_DISABLED;
            case RATE_LIMITED -> MessageKey.CMD_ERR_RATE;
            case UNSUPPORTED -> MessageKey.CMD_ERR_UNSUPPORTED;
            case NOT_CONFIGURED -> MessageKey.CMD_ERR_NOT_CONFIGURED;
            case BAD_REQUEST, TOO_LARGE, INVALID_VALUE -> MessageKey.CMD_ERR_REJECTED;
            case OK, REDIRECT, SERVER_ERROR, NETWORK, TIMEOUT -> MessageKey.CMD_ERR_NETWORK;
        };
    }

    /**
     * One pending command: sends the pending notice once if no answer came within one second. The monitor orders the
     * two, so the notice never follows the answer.
     */
    private final class Reply {

        private final Sender sender;
        private final Scheduler.Cancellable timer;
        private boolean answered;

        Reply(Sender sender) {
            this.sender = sender;
            this.timer = worker.schedule(this::pending, PENDING_AFTER.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void pending() {
            synchronized (this) {
                if (!answered) sender.send(messages.chat(MessageKey.CMD_PENDING));
            }
        }

        void answer(Runnable lines) {
            synchronized (this) {
                answered = true;
            }
            timer.cancel();
            try {
                lines.run();
            } catch (RuntimeException e) {
                log.warn("command.failed", "Kordon: /kordon answer failed: " + e);
                say(sender, MessageKey.CMD_ERR_NETWORK);
            }
        }
    }

    // ── output ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static Actor actor(Sender sender) {
        return sender.console() ? Actor.console() : Actor.player(sender.name(), sender.uuid());
    }

    /** Sends a {@code &}-coded line translated to {@code §} codes. */
    private static void send(Sender sender, String ampersandText) {
        sender.send(Legacy.translate(ampersandText));
    }

    /**
     * Sends a line of the panel's answer as exactly one line with {@code §} codes dropped, so a stored note or ISP name
     * containing a line break cannot forge a line that looks like Kordon's own.
     */
    private static void sendPanelLine(Sender sender, String ampersandText) {
        send(sender, Legacy.plain(ampersandText));
    }

    /** A local reply with the command prefix. */
    private void say(Sender sender, MessageKey key, Object... args) {
        send(sender, messages.get(MessageKey.CMD_PREFIX) + messages.get(key, args));
    }

    /**
     * Non-blank tokens with ASCII edge blanks trimmed (doubled spaces give empty tokens). Other blanks stay because
     * they may be part of a nick ({@link ArgParser#trimTyped}).
     */
    private static String[] tokens(String[] args) {
        if (args == null) return new String[0];
        List<String> out = new ArrayList<>(args.length);
        for (String a : args) {
            if (a != null && !a.isBlank()) out.add(ArgParser.trimTyped(a));
        }
        return out.toArray(new String[0]);
    }
}
