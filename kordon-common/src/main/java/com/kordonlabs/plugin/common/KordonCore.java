package com.kordonlabs.plugin.common;

import com.kordonlabs.plugin.common.api.ApiResult;
import com.kordonlabs.plugin.common.api.KordonApi;
import com.kordonlabs.plugin.common.api.dto.HeartbeatRequest;
import com.kordonlabs.plugin.common.api.dto.HeartbeatResponse;
import com.kordonlabs.plugin.common.command.CommandService;
import com.kordonlabs.plugin.common.command.Linking;
import com.kordonlabs.plugin.common.heartbeat.ConnectionWatch;
import com.kordonlabs.plugin.common.heartbeat.HeartbeatTask;
import com.kordonlabs.plugin.common.http.HttpKordonApi;
import com.kordonlabs.plugin.common.http.HttpTransport;
import com.kordonlabs.plugin.common.http.PingMeter;
import com.kordonlabs.plugin.common.install.ApiBase;
import com.kordonlabs.plugin.common.install.Credentials;
import com.kordonlabs.plugin.common.install.InstallInfo;
import com.kordonlabs.plugin.common.install.InstallReader;
import com.kordonlabs.plugin.common.install.KeyFile;
import com.kordonlabs.plugin.common.install.KeyResolver;
import com.kordonlabs.plugin.common.install.KeySource;
import com.kordonlabs.plugin.common.install.LegacyFiles;
import com.kordonlabs.plugin.common.install.Tokens;
import com.kordonlabs.plugin.common.json.Json;
import com.kordonlabs.plugin.common.kick.KickExecutor;
import com.kordonlabs.plugin.common.login.LoginGate;
import com.kordonlabs.plugin.common.login.LoginHold;
import com.kordonlabs.plugin.common.login.LoginReporter;
import com.kordonlabs.plugin.common.platform.Platform;
import com.kordonlabs.plugin.common.session.EventQueue;
import com.kordonlabs.plugin.common.session.SessionEvent;
import com.kordonlabs.plugin.common.session.SessionTracker;
import com.kordonlabs.plugin.common.state.ConnectionState;
import com.kordonlabs.plugin.common.state.ConnectionStateHolder;
import com.kordonlabs.plugin.common.state.ServerSettings;
import com.kordonlabs.plugin.common.state.SettingsHolder;
import com.kordonlabs.plugin.common.sync.SyncTask;
import com.kordonlabs.plugin.common.text.MessageKey;
import com.kordonlabs.plugin.common.text.Messages;
import com.kordonlabs.plugin.common.util.Clock;
import com.kordonlabs.plugin.common.util.ExecutorScheduler;
import com.kordonlabs.plugin.common.util.MachineName;
import com.kordonlabs.plugin.common.util.ProxyIdentity;
import com.kordonlabs.plugin.common.util.Scheduler;
import com.kordonlabs.plugin.common.util.SystemClock;
import com.kordonlabs.plugin.common.util.ThrottledLog;
import com.kordonlabs.plugin.common.util.Threads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Facade and lifecycle of the platform-agnostic core. A platform module creates one instance per enable and drives
 * it: {@code core = new KordonCore(platform); core.start(); ... core.stop(Duration.ofSeconds(2))}.
 *
 * <p>{@link #start()} never throws. If wiring fails, it logs the error, releases what it created and stays inactive,
 * so the proxy keeps running unprotected but otherwise untouched. Without a key the core is NOT_CONFIGURED: it makes
 * no network calls and reminds the console every 5 minutes until {@code /kordon link} or a restart with a downloaded
 * jar. All settings come from the panel and live in memory only; until the first answer
 * {@link ServerSettings#DEFAULTS} apply.
 *
 * <p>The only file the core writes is {@code plugins/Kordon/secret.key} ({@link KeyFile}).
 *
 * <p>{@code /kordon link} and {@code unlink} swap components only on {@code Kordon-Worker}. The old sync sends its
 * final request with the old key, blocking the worker for up to 2 seconds; logins are unaffected because they run on
 * their own threads and the login timer is a separate executor.
 *
 * <p>{@link #stop(Duration)} shuts down in a fixed order so that plugin unloads and proxy stops leak no threads. The
 * component accessors return null before {@code start()} and after {@code stop()}, so platform listeners check
 * {@link #active()} first.
 */
public final class KordonCore implements Linking {

    /**
     * Result of {@link #reload()}.
     *
     * @param jarReplaced a different Kordon.jar was copied over the running one; it takes effect after a proxy restart
     *                    and the running credentials are kept
     * @param configured  the plugin has a key, so an immediate heartbeat was requested to refresh the settings
     */
    public record ReloadResult(boolean jarReplaced, boolean configured) {
    }

    static final int LOGIN_THREADS = 4;
    static final int HTTP_THREADS = 4;
    static final int LOGIN_HTTP_THREADS = 2;
    /** How long all executors together may take to terminate on stop. */
    static final Duration EXECUTOR_AWAIT = Duration.ofMillis(500);
    static final Duration NOT_CONFIGURED_REMINDER = Duration.ofMinutes(5);
    static final Duration RELOAD_TIMEOUT = Duration.ofSeconds(30);
    /** Budget for the old instance's final sync during {@code /kordon link} and {@code unlink}. */
    static final Duration RELINK_FLUSH = Duration.ofSeconds(2);
    /** {@code /kordon link} and {@code unlink} always reply within this time. */
    static final Duration LINK_TIMEOUT = Duration.ofSeconds(30);

    private final Platform platform;
    private final Clock clock;
    private final Scheduler testScheduler;

    private final Object lifecycle = new Object();
    private volatile boolean started;
    private volatile boolean stopped;
    private final AtomicBoolean loopsStarted = new AtomicBoolean();
    /** Set while a {@code /kordon link} or {@code unlink} is running; only one may run at a time. */
    private final AtomicBoolean linking = new AtomicBoolean();

    // The two scheduled executors stay null when a test scheduler replaces them.
    private ScheduledThreadPoolExecutor workerExecutor;
    private ScheduledThreadPoolExecutor loginTimerExecutor;
    private ExecutorService loginExecutor;
    private ExecutorService eventsExecutor;
    private ExecutorService httpExecutor;
    private ExecutorService loginHttpExecutor;
    private volatile Scheduler worker;
    private volatile Scheduler loginTimer;

    // Components: written by start() (and by link/unlink on the worker), read by platform threads, cleared by stop().
    private volatile Messages messages;
    private volatile ThrottledLog log;
    /** The embedded install info read at start. Reload never replaces it because the running jar cannot change. */
    private volatile InstallInfo install = InstallInfo.NONE;
    private volatile String instance;
    /** The API address and key in use; replaced by link/unlink and reset to {@link Credentials#NONE} on stop. */
    private volatile Credentials credentials = Credentials.NONE;
    private volatile KeyFile keyFile;
    private volatile SettingsHolder settings;
    private volatile ConnectionStateHolder state;
    private volatile ConnectionWatch watch;
    private volatile ProxyIdentity identity;
    private volatile PingMeter ping;
    private volatile HttpTransport transport;
    private volatile KordonApi api;
    private volatile LoginGate loginGate;
    private volatile LoginHold loginHold;
    private volatile SessionTracker sessions;
    private volatile KickExecutor kicks;
    private volatile HeartbeatTask heartbeat;
    private volatile SyncTask syncTask;
    private volatile CommandService commands;
    private volatile Scheduler.Cancellable notConfiguredReminder;

    public KordonCore(Platform platform) {
        this(platform, SystemClock.INSTANCE, null);
    }

    KordonCore(Platform platform, Clock clock, Scheduler scheduler) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.testScheduler = scheduler;
    }

    /** Wires the core and starts the first heartbeat asynchronously. Never throws; runs at most once. */
    public void start() {
        synchronized (lifecycle) {
            if (started || stopped) return;
            try {
                wire();
                started = true;
            } catch (Throwable t) {
                failStart(t);
            }
        }
    }

    /** Stops every component in order. Blocks for at most {@code flushBudget} plus about one second. Idempotent. */
    public void stop(Duration flushBudget) {
        synchronized (lifecycle) {
            if (stopped) return;
            // From here listeners return at once and no new syncs or heartbeats start.
            stopped = true;
        }
        Duration budget = flushBudget == null || flushBudget.isNegative() ? Duration.ZERO : flushBudget;
        step("cancel reminder", () -> {
            Scheduler.Cancellable reminder = notConfiguredReminder;
            if (reminder != null) reminder.cancel();
        });
        step("halt heartbeat", () -> {
            if (heartbeat != null) heartbeat.stop();
        });
        step("halt sync", () -> {
            if (syncTask != null) syncTask.halt();
        });
        // Pending logins get the fallback verdict; late HTTP answers then lose the race and are ignored.
        step("release pending logins", () -> {
            if (loginHold != null) loginHold.releaseAll();
        });
        // The final sync reports remaining quits with stopping=true; it gives up silently after the budget.
        step("final sync", () -> {
            if (started && syncTask != null && credentials.configured()) {
                syncTask.finalSync(budget);
            }
        });
        step("close HTTP clients", () -> {
            if (transport != null) transport.close();
        });
        step("stop threads", this::shutdownExecutors);
        // Drop references so the plugin class loader can be collected.
        clearComponents();
    }

    /**
     * {@code /kordon reload}. Runs on {@code Kordon-Worker} so that zip I/O never happens on a proxy thread. Compares
     * the jar on disk with the one read at start and, when configured, requests an immediate heartbeat to refresh the
     * settings. Completes exceptionally when the core is not running.
     */
    public CompletableFuture<ReloadResult> reload() {
        CompletableFuture<ReloadResult> result = new CompletableFuture<>();
        Scheduler w = worker;
        if (!started || stopped || w == null) {
            result.completeExceptionally(new IllegalStateException("Kordon is not running"));
            return result;
        }
        w.execute(() -> {
            try {
                result.complete(reloadOnWorker());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        // A task dropped by a stopping worker must not leave the command waiting forever.
        return result.orTimeout(RELOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Called once, right after the platform registered its listeners, so that players already online (for example
     * after a plugin reload) get a session. The scan runs on {@code Kordon-Worker} strictly after the registration,
     * so no player is missed, and a player seen by both the scan and a listener is tracked once. Does nothing while
     * NOT_CONFIGURED.
     */
    public void listenersReady() {
        Scheduler w = worker;
        SessionTracker tracker = sessions;
        if (!started || stopped || w == null || tracker == null) return;
        w.execute(() -> {
            ConnectionStateHolder s = state;
            if (!stopped && s != null && s.get() != ConnectionState.NOT_CONFIGURED) tracker.registerOnlineAtStart();
        });
    }

    /** False before start, after a failed start, while NOT_CONFIGURED (no key) and after stop. */
    public boolean active() {
        ConnectionStateHolder s = state;
        return started && !stopped && s != null && s.get() != ConnectionState.NOT_CONFIGURED;
    }

    /** True once {@link #start()} completed the wiring, including while NOT_CONFIGURED. */
    public boolean started() {
        return started;
    }

    public boolean stopped() {
        return stopped;
    }

    public Platform platform() {
        return platform;
    }

    /** Null before start and after stop. */
    public LoginGate loginGate() {
        return loginGate;
    }

    /** Null before start and after stop. */
    public LoginHold loginHold() {
        return loginHold;
    }

    /** Null before start and after stop. */
    public SessionTracker sessions() {
        return sessions;
    }

    /** Null before start and after stop. */
    public CommandService commands() {
        return commands;
    }

    /** Null only before start. */
    public Messages messages() {
        return messages;
    }

    /** Null only before start. */
    public ThrottledLog log() {
        return log;
    }


    HttpTransport transport() {
        return transport;
    }

    String instance() {
        return instance;
    }

    Credentials credentials() {
        return credentials;
    }

    ConnectionState connectionState() {
        ConnectionStateHolder s = state;
        return s == null ? null : s.get();
    }

    // ── start ────────────────────────────────────────────────────────────────────────────────────────────────────

    private void wire() {
        Logger logger = platform.logger();
        Messages texts = Messages.load();
        messages = texts;
        ThrottledLog console = new ThrottledLog(logger, clock);
        log = console;
        Json.debugLog(console::debug);

        // Threads first: the components below schedule on them.
        loginExecutor = Threads.fixed("Kordon-Login", LOGIN_THREADS, logger);
        eventsExecutor = Threads.single("Kordon-Events", logger);
        httpExecutor = Threads.fixed("Kordon-HTTP", HTTP_THREADS, logger);
        loginHttpExecutor = Threads.fixed("Kordon-LoginHTTP", LOGIN_HTTP_THREADS, logger);
        if (testScheduler != null) {
            worker = testScheduler;
            loginTimer = testScheduler;
        } else {
            workerExecutor = Threads.scheduled("Kordon-Worker", logger);
            loginTimerExecutor = Threads.scheduled("Kordon-Login-Timer", logger);
            worker = new ExecutorScheduler(workerExecutor);
            loginTimer = new ExecutorScheduler(loginTimerExecutor);
        }

        // plugins/Kordon holds at most secret.key; the API address is never stored.
        console.setDebug(debugFlag());
        Path dataFolder = platform.dataFolder();
        warnLegacyConfig(dataFolder, texts, console);
        LegacyFiles.cleanUp(dataFolder, console);
        InstallInfo embedded = new InstallReader(console).read(platform.pluginJar());
        install = embedded;
        ApiBase.Resolution base = ApiBase.resolve(systemProperty(KordonInfo.PANEL_URL_PROPERTY), embedded,
                KordonInfo.defaultApiUrl());
        if (base.propertyInvalid()) console.warn(texts.console(MessageKey.LOG_PANEL_URL_PROPERTY_INVALID));
        if (base.origin() == ApiBase.Origin.PROPERTY) {
            console.info(texts.console(MessageKey.LOG_PANEL_URL_PROPERTY, "apiUrl", base.url()));
        }
        if (base.legacyNormalised()) {
            console.debug("the address built into Kordon.jar is an old Kordon address: using " + ApiBase.KORDON_API);
        }
        KeyFile file = new KeyFile(dataFolder);
        keyFile = file;
        KeyResolver.Resolution key = new KeyResolver(file, platform.pluginJar(), console, texts).resolve(embedded);
        Credentials resolved = new Credentials(base.url(), key.token(), key.source());
        credentials = resolved;
        // Warn even while NOT_CONFIGURED: /kordon link would send the key to this address.
        if (resolved.plainHttpPublic()) {
            console.warn(texts.console(MessageKey.LOG_PANEL_HTTP, "apiUrl", resolved.apiUrl()));
        }

        // State lives in memory only; DEFAULTS apply until the first panel answer.
        SettingsHolder settingsHolder = new SettingsHolder();
        settings = settingsHolder;
        ConnectionStateHolder stateHolder = new ConnectionStateHolder(clock);
        state = stateHolder;
        instance = UUID.randomUUID().toString();
        ConnectionWatch connectionWatch = new ConnectionWatch(console, texts, clock);
        watch = connectionWatch;
        identity = new ProxyIdentity(dataFolder, () -> MachineName.resolve(console::debug));

        // A dedicated HTTP client for /login, another for all background traffic.
        HttpTransport http = new HttpTransport(KordonInfo.userAgent(platform.platformId()), loginHttpExecutor,
                httpExecutor);
        transport = http;
        PingMeter pingMeter = new PingMeter(clock);
        ping = pingMeter;
        KordonApi kordonApi = new HttpKordonApi(http, () -> credentials, console, pingMeter); // the live field per call
        api = kordonApi;

        // Login path: isolated threads; each held login is completed exactly once.
        LoginGate gate = new LoginGate(kordonApi, settingsHolder, stateHolder, console, clock, texts);
        loginGate = gate;
        LoginReporter reporter = new LoginReporter(platform, texts, console, eventsExecutor, clock, connectionWatch);
        loginHold = new LoginHold(gate, reporter, loginTimer, loginExecutor, console);

        SessionTracker tracker = new SessionTracker(platform, new EventQueue(EventQueue.CAPACITY), clock, worker,
                console, this::onEnqueued);
        sessions = tracker;
        kicks = new KickExecutor(platform, tracker, texts, console, eventsExecutor, clock);
        heartbeat = newHeartbeat(instance);
        syncTask = newSync(instance);
        commands = new CommandService(platform, kordonApi, settingsHolder, stateHolder, () -> credentials, tracker,
                kicks, texts, console, worker, clock, () -> instance, this::reload, this);

        if (resolved.configured()) {
            logStart(resolved);
            startLoops();
            // Players already online get a session in listenersReady(), once the platform's listeners exist.
        } else {
            stateHolder.set(ConnectionState.NOT_CONFIGURED);
            remindNotConfigured();
        }
    }

    private HeartbeatTask newHeartbeat(String inst) {
        return new HeartbeatTask(platform, api, () -> credentials, embeddedServerId(), settings, state, watch,
                messages, log, worker, clock, inst, ping, identity, this::onHeartbeatOk);
    }

    private SyncTask newSync(String inst) {
        return new SyncTask(api, sessions, kicks, settings, state, () -> credentials, watch, platform, messages, log,
                worker, clock, inst, this::requestHeartbeat);
    }

    /** The jar's {@code serverId}, but only while the jar's key is the one in use. */
    private String embeddedServerId() {
        InstallInfo i = install;
        Credentials c = credentials;
        return i.present() && i.token().equals(c.token()) ? i.serverId() : null;
    }

    private void failStart(Throwable error) {
        try {
            Messages texts = messages;
            String line = texts == null ? "Kordon failed to start (" + error + ")"
                    : texts.console(MessageKey.LOG_START_FAILED, "error", String.valueOf(error));
            platform.logger().log(Level.SEVERE, Tokens.maskAll(line), error);
        } catch (Throwable ignored) {
            // Nothing left to report with.
        }
        stopped = true;
        try {
            if (transport != null) transport.close();
        } catch (Throwable ignored) {
            // A half-built transport: its threads die with the executors below.
        }
        shutdownExecutors();
        clearComponents();
    }

    private void logStart(Credentials c) {
        KeySource source = c.source() == null ? KeySource.NONE : c.source();
        log.info(messages.console(MessageKey.LOG_START,
                "version", KordonInfo.version(),
                "platform", platformName(),
                "serverVersion", platform.serverVersion(),
                "apiUrl", c.apiUrl(),
                "tokenPrefix", c.tokenPrefix(),
                "keySource", source.label() == null ? "" : messages.english().get(source.label())));
    }

    private static boolean debugFlag() {
        return KordonInfo.debugRequested(systemProperty(KordonInfo.DEBUG_PROPERTY));
    }

    private static String systemProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A {@code config.yml} left by an older plugin is no longer used: one console line per start, never deleted. */
    private static void warnLegacyConfig(Path dataFolder, Messages texts, ThrottledLog console) {
        try {
            if (dataFolder != null && Files.isRegularFile(dataFolder.resolve("config.yml"))) {
                console.info(texts.console(MessageKey.LOG_LEGACY_CONFIG));
            }
        } catch (RuntimeException e) {
            console.debug(() -> "legacy config check failed: " + e);
        }
    }

    private String platformName() {
        String id = platform.platformId();
        return messages.getOr("platform." + id, id);
    }

    private void startLoops() {
        if (!loopsStarted.compareAndSet(false, true)) return;
        heartbeat.start();
        syncTask.start();
    }

    /** Logs the not-configured reminder now and every 5 minutes while NOT_CONFIGURED, using a single timer. */
    private void remindNotConfigured() {
        ConnectionStateHolder s = state;
        if (stopped || s == null || s.get() != ConnectionState.NOT_CONFIGURED) return;
        Scheduler.Cancellable previous = notConfiguredReminder;
        if (previous != null) previous.cancel();
        log.warn(messages.console(MessageKey.LOG_NOT_CONFIGURED, "panelUrl", ApiBase.pagesUrl(credentials.apiUrl())));
        notConfiguredReminder = worker.schedule(this::remindNotConfigured, NOT_CONFIGURED_REMINDER.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    // ── callbacks between components (ignored once stopping) ─────────────────────────────────────────────────────

    private void onEnqueued(SessionEvent.Kind kind) {
        SyncTask task = syncTask;
        if (task != null && !stopped) task.nudge(kind);
    }

    private void onHeartbeatOk(ServerSettings newSettings) {
        SyncTask task = syncTask;
        if (task != null && !stopped) task.onHeartbeat(newSettings);
    }

    private void requestHeartbeat() {
        HeartbeatTask task = heartbeat;
        if (task != null && !stopped) task.requestNow();
    }

    // ── reload ───────────────────────────────────────────────────────────────────────────────────────────────────

    private ReloadResult reloadOnWorker() {
        if (stopped) throw new IllegalStateException("Kordon is stopping");
        // The running jar cannot be swapped: compare only and keep the credentials read at start.
        InstallInfo onDisk = new InstallReader(log).read(platform.pluginJar());
        boolean jarReplaced = !onDisk.fingerprint().equals(install.fingerprint());
        if (jarReplaced) log.warn(messages.console(MessageKey.LOG_JAR_REPLACED));
        // An immediate heartbeat refreshes the settings and also resets the sync backoff.
        boolean configured = credentials.configured() && state.get() != ConnectionState.NOT_CONFIGURED;
        if (configured) heartbeat.requestNow();
        return new ReloadResult(jarReplaced, configured);
    }

    // ── /kordon link, /kordon unlink ─────────────────────────────────────────────────────────────────────────────

    /**
     * Validates {@code key} with a heartbeat under a new instance id. Only a successful answer saves
     * {@code secret.key} and switches to the new key; any other outcome changes nothing. The future completes on
     * {@code Kordon-Worker} within {@link #LINK_TIMEOUT}.
     */
    @Override
    public CompletableFuture<LinkResult> link(String key, String actor) {
        CompletableFuture<LinkResult> result = new CompletableFuture<>();
        Scheduler w = worker;
        Credentials current = credentials;
        if (!started || stopped || w == null) return done(result, LinkResult.of(Outcome.NOT_RUNNING));
        if (!Tokens.valid(key)) return done(result, LinkResult.of(Outcome.INVALID));
        String prefix = Tokens.prefix(key);
        if (current.apiUrl() == null) return done(result, LinkResult.of(Outcome.NO_API));
        if (key.equals(current.token())) {
            return done(result, new LinkResult(Outcome.SAME_KEY, null, prefix, current.apiUrl(), null, 0, null));
        }
        if (!linking.compareAndSet(false, true)) return done(result, LinkResult.of(Outcome.BUSY));
        result.whenComplete((r, e) -> linking.set(false));
        result.orTimeout(LINK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        w.execute(() -> {
            try {
                validate(w, key, actor, result);
            } catch (Throwable t) {
                log.debug(() -> "kordon link failed: " + t);
                result.complete(new LinkResult(Outcome.UNREACHABLE, null, prefix, credentials.apiUrl(), "network",
                        0, null));
            }
        });
        return result;
    }

    /** Runs on the worker: sends the validation heartbeat with the candidate credentials. */
    private void validate(Scheduler w, String key, String actor, CompletableFuture<LinkResult> result) {
        if (stopped) {
            result.complete(LinkResult.of(Outcome.NOT_RUNNING));
            return;
        }
        String next = UUID.randomUUID().toString();
        Credentials candidate = new Credentials(credentials.apiUrl(), key, KeySource.LINK);
        HeartbeatRequest request = HeartbeatTask.request(platform, next, identity, ping);
        CompletableFuture<ApiResult<HeartbeatResponse>> call;
        try {
            call = api.heartbeat(request, candidate);
        } catch (Throwable t) {
            call = CompletableFuture.completedFuture(ApiResult.noAnswer(ApiResult.Outcome.NETWORK));
        }
        // A stopping worker drops the task; the timeout then completes the command.
        call.whenComplete((answer, error) -> w.execute(() -> {
            try {
                validated(candidate, next, actor, error != null || answer == null
                        ? ApiResult.noAnswer(ApiResult.Outcome.NETWORK) : answer, result);
            } catch (Throwable t) {
                log.debug(() -> "kordon link answer not handled: " + t);
                result.complete(LinkResult.of(Outcome.NOT_RUNNING));
            }
        }));
    }

    /** Runs on the worker: handles the answer to the validation heartbeat. */
    private void validated(Credentials candidate, String next, String actor, ApiResult<HeartbeatResponse> r,
                           CompletableFuture<LinkResult> result) {
        String prefix = candidate.tokenPrefix();
        String apiUrl = candidate.apiUrl();
        if (stopped) {
            result.complete(LinkResult.of(Outcome.NOT_RUNNING));
            return;
        }
        if (result.isDone()) {
            // LINK_TIMEOUT passed and the command already reported a failure, so nothing may be written or switched.
            log.debug(() -> "kordon link: the answer came after the command gave up, ignored");
            return;
        }
        switch (r.outcome()) {
            case OK -> {
                try {
                    keyFile.write(candidate.token());
                } catch (Exception e) {
                    linkFailed(prefix, actor, MessageKey.CAUSE_SAVE_FAILED);
                    result.complete(new LinkResult(Outcome.SAVE_FAILED, null, prefix, apiUrl, null, 0,
                            e.getClass().getSimpleName()));
                    return;
                }
                HeartbeatResponse body = r.body();
                String server = body.server() == null ? null : body.server().name();
                relink(candidate, next, body, actor, server);
                result.complete(new LinkResult(Outcome.LINKED, server, prefix, apiUrl, null, 0, null));
            }
            case UNAUTHORIZED -> {
                linkFailed(prefix, actor, MessageKey.CAUSE_UNAUTHORIZED);
                result.complete(new LinkResult(Outcome.REFUSED, null, prefix, apiUrl, null, 0, null));
            }
            case ACCOUNT_DISABLED -> {
                linkFailed(prefix, actor, MessageKey.CAUSE_ACCOUNT_DISABLED);
                result.complete(new LinkResult(Outcome.ACCOUNT_DISABLED, null, prefix, apiUrl, null, 0, null));
            }
            case RATE_LIMITED -> {
                linkFailed(prefix, actor, MessageKey.CAUSE_RATE_LIMITED);
                result.complete(new LinkResult(Outcome.RATE_LIMITED, null, prefix, apiUrl, null,
                        Math.max(1, r.retryAfterSec()), null));
            }
            default -> {
                String cause = switch (r.outcome()) {
                    // cause.timeout mentions the login's 3 s limit, which does not apply to a heartbeat.
                    case NETWORK, TIMEOUT -> "network";
                    case SERVER_ERROR -> "server_error";
                    case REDIRECT -> "redirect";
                    default -> "unsupported";
                };
                linkFailed(prefix, actor, MessageKey.byKey("cause." + cause).orElse(MessageKey.CAUSE_NETWORK));
                result.complete(new LinkResult(Outcome.UNREACHABLE, null, prefix, apiUrl, cause, 0, null));
            }
        }
    }

    private void linkFailed(String prefix, String actor, MessageKey why) {
        log.warn(messages.console(MessageKey.LOG_LINK_FAILED, "tokenPrefix", prefix,
                "actor", Messages.literal(actor), "why", messages.english().get(why)));
    }

    /**
     * Runs on the worker after a validated key was saved. The old sync sends a final {@code stopping} request with the
     * old key so the panel closes the old instance's sessions. Then the new key, instance, heartbeat and sync loop take
     * over, the validation answer is applied at once and everyone online gets a new session.
     */
    private void relink(Credentials candidate, String next, HeartbeatResponse body, String actor, String server) {
        Credentials old = credentials;
        HeartbeatTask oldHeartbeat = heartbeat;
        SyncTask oldSync = syncTask;
        if (oldHeartbeat != null) oldHeartbeat.stop();
        if (oldSync != null) {
            if (old.configured() && loopsStarted.get()) oldSync.finalSync(RELINK_FLUSH);
            else oldSync.halt();
        }
        Scheduler.Cancellable reminder = notConfiguredReminder;
        if (reminder != null) reminder.cancel();
        notConfiguredReminder = null;

        credentials = candidate;
        instance = next;
        forgetLossLines();
        loginGate.forgetVerdicts();
        sessions.clear();
        settings.set(ServerSettings.DEFAULTS);
        state.set(ConnectionState.UNREACHABLE);
        heartbeat = newHeartbeat(next);
        syncTask = newSync(next);
        syncTask.start();
        heartbeat.prime(body);
        heartbeat.start(Duration.ofSeconds(settings.get().heartbeatIntervalSec()));
        loopsStarted.set(true);
        sessions.registerOnlineAtStart();
        log.info(messages.console(MessageKey.LOG_LINKED, "server", Messages.literal(server == null ? "" : server),
                "tokenPrefix", candidate.tokenPrefix(), "actor", Messages.literal(actor)));
    }

    /** Deletes {@code secret.key}; players join unchecked until the next link. Completes on the worker. */
    @Override
    public CompletableFuture<UnlinkResult> unlink(String actor) {
        CompletableFuture<UnlinkResult> result = new CompletableFuture<>();
        Scheduler w = worker;
        if (!started || stopped || w == null) return done(result, UnlinkResult.of(UnlinkOutcome.NOT_RUNNING));
        if (!linking.compareAndSet(false, true)) return done(result, UnlinkResult.of(UnlinkOutcome.BUSY));
        result.whenComplete((r, e) -> linking.set(false));
        result.orTimeout(LINK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        w.execute(() -> {
            if (result.isDone()) return; // LINK_TIMEOUT passed and the command already reported a failure
            try {
                result.complete(unlinkOnWorker(actor));
            } catch (Throwable t) {
                result.complete(new UnlinkResult(UnlinkOutcome.FAILED, null, t.getClass().getSimpleName()));
            }
        });
        return result;
    }

    private UnlinkResult unlinkOnWorker(String actor) {
        if (stopped) return UnlinkResult.of(UnlinkOutcome.NOT_RUNNING);
        boolean deleted;
        try {
            deleted = keyFile.delete();
        } catch (Exception e) {
            return new UnlinkResult(UnlinkOutcome.FAILED, null, e.getClass().getSimpleName());
        }
        Credentials old = credentials;
        if (!deleted && !old.configured()) return UnlinkResult.of(UnlinkOutcome.NOT_LINKED);
        HeartbeatTask oldHeartbeat = heartbeat;
        SyncTask oldSync = syncTask;
        if (oldHeartbeat != null) oldHeartbeat.stop();
        if (oldSync != null) {
            if (old.configured() && loopsStarted.get()) oldSync.finalSync(RELINK_FLUSH);
            else oldSync.halt();
        }
        credentials = new Credentials(old.apiUrl(), null, KeySource.NONE);
        state.set(ConnectionState.NOT_CONFIGURED);
        settings.set(ServerSettings.DEFAULTS);
        sessions.clear();
        loginGate.forgetVerdicts();
        watch.reset();
        forgetLossLines();
        heartbeat = newHeartbeat(instance);
        syncTask = newSync(instance);
        loopsStarted.set(false);
        log.info(messages.console(MessageKey.LOG_UNLINKED, "actor", Messages.literal(actor)));
        remindNotConfigured();
        InstallInfo i = install;
        return new UnlinkResult(UnlinkOutcome.UNLINKED, i.present() ? Tokens.prefix(i.token()) : null, null);
    }

    /** Resets the throttling of connection-loss warnings so that a problem with the new key is reported at once. */
    private void forgetLossLines() {
        log.reset(HeartbeatTask.LOG_UNAUTHORIZED);
        log.reset(HeartbeatTask.LOG_BAD_PANEL_URL);
        log.reset(HeartbeatTask.LOG_PANEL_REDIRECT);
        log.reset(SyncTask.LOG_SYNC_FAILING);
    }

    private static <T> CompletableFuture<T> done(CompletableFuture<T> future, T value) {
        future.complete(value);
        return future;
    }

    // ── stop ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            try {
                ThrottledLog console = log;
                if (console != null) console.error("Kordon stop: " + name + " failed: " + t, t);
                else platform.logger().log(Level.WARNING, "Kordon stop: " + name + " failed", t);
            } catch (Throwable ignored) {
                // Keep stopping.
            }
        }
    }

    private void shutdownExecutors() {
        Threads.shutdown(Arrays.asList(workerExecutor, eventsExecutor, loginTimerExecutor, loginExecutor,
                httpExecutor, loginHttpExecutor), EXECUTOR_AWAIT);
    }

    private void clearComponents() {
        Json.debugLog(null);
        workerExecutor = null;
        loginTimerExecutor = null;
        loginExecutor = null;
        eventsExecutor = null;
        httpExecutor = null;
        loginHttpExecutor = null;
        worker = null;
        loginTimer = null;
        notConfiguredReminder = null;
        credentials = Credentials.NONE;
        keyFile = null;
        settings = null;
        state = null;
        watch = null;
        identity = null;
        ping = null;
        transport = null;
        api = null;
        loginGate = null;
        loginHold = null;
        sessions = null;
        kicks = null;
        heartbeat = null;
        syncTask = null;
        commands = null;
    }
}
