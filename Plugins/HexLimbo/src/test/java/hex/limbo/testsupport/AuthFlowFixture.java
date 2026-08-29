package hex.limbo.testsupport;

import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.FlowResultApplier;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.premium.PremiumResolver;
import hex.limbo.prompt.PromptService;
import hex.limbo.security.RateLimiter;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires up the <b>real</b> {@link AuthFlow}, {@link AuthService}, {@link PromptService} and
 * {@link ConnectionRegistry} with recording collaborators.
 *
 * <p>Tests drive production code through this fixture rather than a hand-written imitation of the
 * listener/command sequence, so the tested ordering cannot drift away from the shipped ordering.
 * The only things replaced are the edges the flow already abstracts - the session store, the audit
 * log, the account repository and the Mojang resolver - plus the proxy scheduler.
 *
 * <p>{@link BlockingRepository} lets a test pause the flow at the exact instant it is about to make
 * a persistent write, which is what makes the commit-point races deterministic instead of timed.
 */
public final class AuthFlowFixture {

    /** An account repository that can be held at a chosen write, and can be made to fail. */
    public static final class BlockingRepository implements AccountRepository {

        private final AccountRepository delegate;

        /** Reached just before the named write executes. */
        public final Map<String, CountDownLatch> reached = new ConcurrentHashMap<>();
        /** The named write waits on this before executing. */
        public final Map<String, CountDownLatch> release = new ConcurrentHashMap<>();
        /** The named write throws instead of executing. */
        public final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();
        public final List<String> writes = new CopyOnWriteArrayList<>();

        public BlockingRepository(AccountRepository delegate) {
            this.delegate = delegate;
        }

        /** Arms a pause at {@code write}; the returned latch fires when the flow reaches it. */
        public CountDownLatch pauseAt(String write) {
            return pauseAt(write, 1);
        }

        /**
         * Arms a pause at {@code write} for {@code arrivals} callers. The returned latch only opens
         * once that many threads are simultaneously held at the write, which is how a test proves
         * two operations really are running in parallel rather than hoping they are.
         */
        public CountDownLatch pauseAt(String write, int arrivals) {
            CountDownLatch arrived = new CountDownLatch(arrivals);
            reached.put(write, arrived);
            release.put(write, new CountDownLatch(1));
            return arrived;
        }

        /** Lets a paused write proceed. */
        public void resume(String write) {
            CountDownLatch latch = release.get(write);
            if (latch != null) {
                latch.countDown();
            }
        }

        public void failAt(String write, RuntimeException failure) {
            failures.put(write, failure);
        }

        private void gate(String write) {
            writes.add(write);
            CountDownLatch arrived = reached.get(write);
            if (arrived != null) {
                arrived.countDown();
                CountDownLatch go = release.get(write);
                try {
                    if (go != null && !go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("write '" + write + "' was never released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            RuntimeException failure = failures.get(write);
            if (failure != null) {
                throw failure;
            }
        }

        @Override public void initializeSchema() { delegate.initializeSchema(); }
        @Override public Optional<Account> findByUsername(String u) { return delegate.findByUsername(u); }
        @Override public Optional<Account> findByUuid(UUID u) { return delegate.findByUuid(u); }
        @Override public int countByIp(String h) { return delegate.countByIp(h); }
        @Override public void close() { delegate.close(); }

        @Override
        public Account create(Account account) {
            gate("create");
            return delegate.create(account);
        }

        @Override
        public void recordSuccessfulLogin(long id, long now, String ipHash, String username) {
            gate("recordSuccessfulLogin");
            delegate.recordSuccessfulLogin(id, now, ipHash, username);
        }

        @Override
        public void updatePasswordHash(long id, String hash) {
            gate("updatePasswordHash");
            delegate.updatePasswordHash(id, hash);
        }

        @Override
        public void updateAccountType(long id, AccountType type) {
            gate("updateAccountType");
            delegate.updateAccountType(id, type);
        }

        @Override
        public void updateFailedAttempts(long id, int n, Long until) {
            gate("updateFailedAttempts");
            delegate.updateFailedAttempts(id, n, until);
        }

        @Override
        public void updatePremiumUuid(long id, UUID u) {
            gate("updatePremiumUuid");
            delegate.updatePremiumUuid(id, u);
        }

        @Override
        public void updateUuid(long id, UUID u) {
            gate("updateUuid");
            delegate.updateUuid(id, u);
        }

        @Override
        public void delete(long id) {
            gate("delete");
            delegate.delete(id);
        }

        @Override
        public boolean promotePendingMigrationToPremium(long id, UUID uuid, long now, String ipHash, String name) {
            gate("promotePendingMigrationToPremium");
            return delegate.promotePendingMigrationToPremium(id, uuid, now, ipHash, name);
        }
    }

    /** One recorded session row. */
    public record SessionRow(long accountId, UUID uuid, String usernameLower, String ipHash) {}

    /** One recorded audit entry. */
    public record AuditEntry(String action, String usernameLower, UUID uuid, String ipHash, String detail) {}

    /**
     * Recording session store with a switchable "valid session exists" answer.
     *
     * <p>Besides the raw call log it models what the real store does - {@link #live} holds the UUIDs
     * that currently have a session row - so a test can assert on the <em>outcome</em> ("this player
     * has no valid session left") rather than on the call sequence that produced it.
     */
    public static final class RecordingSessions implements AuthFlow.Sessions {
        public final List<SessionRow> created = new CopyOnWriteArrayList<>();
        public final List<UUID> invalidated = new CopyOnWriteArrayList<>();
        /** UUIDs with a session row right now: created adds, invalidate removes. */
        public final Set<UUID> live = ConcurrentHashMap.newKeySet();
        public volatile boolean sessionValid;

        @Override
        public void createSession(long accountId, UUID uuid, String usernameLower, String ipHash) {
            created.add(new SessionRow(accountId, uuid, usernameLower, ipHash));
            live.add(uuid);
        }

        @Override
        public Optional<Long> findValidSessionExpiry(UUID uuid, String ipHash) {
            return sessionValid ? Optional.of(System.currentTimeMillis() + 60_000L) : Optional.empty();
        }

        @Override
        public void invalidate(UUID uuid) {
            invalidated.add(uuid);
            live.remove(uuid);
        }

        /** Whether {@code uuid} still has a session row that was not invalidated afterwards. */
        public boolean hasLiveSession(UUID uuid) {
            return live.contains(uuid);
        }
    }

    /**
     * A {@link RouteCoordinator.Scheduler} that never runs anything on its own.
     *
     * <p>Retries and watchdogs are therefore visible as pending tasks a test can inspect, run or
     * watch being cancelled - which is what makes bounded recovery and timeouts assertable without
     * a single sleep.
     */
    public static final class ManualScheduler implements RouteCoordinator.Scheduler {

        /** One scheduled task and whether it is still armed. */
        public static final class Task {
            public final long delayMillis;
            private final Runnable body;
            private volatile boolean cancelled;
            private volatile boolean ran;

            Task(long delayMillis, Runnable body) {
                this.delayMillis = delayMillis;
                this.body = body;
            }

            public boolean isPending() {
                return !cancelled && !ran;
            }

            public boolean isCancelled() {
                return cancelled;
            }
        }

        public final List<Task> scheduled = new CopyOnWriteArrayList<>();
        /**
         * Runs once, the next time a scheduled task is cancelled.
         *
         * <p>The coordinator cancels an attempt's watchdog between releasing the routing lock and
         * running the terminal path, so this is the one injection point that lands inside that
         * window without a hook in production code.
         */
        public volatile Runnable onNextCancel;

        @Override
        public ConnectionHandle.Cancellable schedule(long delayMillis, Runnable task) {
            Task entry = new Task(delayMillis, task);
            scheduled.add(entry);
            return () -> {
                entry.cancelled = true;
                Runnable hook = onNextCancel;
                if (hook != null) {
                    onNextCancel = null;
                    hook.run();
                }
            };
        }

        /** Tasks that have neither run nor been cancelled. */
        public List<Task> pending() {
            return scheduled.stream().filter(Task::isPending).toList();
        }

        /** Runs every task that is still armed, as the clock reaching their delay would. */
        public void runPending() {
            for (Task task : List.copyOf(scheduled)) {
                if (task.isPending()) {
                    task.ran = true;
                    task.body.run();
                }
            }
        }

        /** Runs only the armed task with the longest delay - the watchdog, in practice. */
        public void runLongestPending() {
            pending().stream()
                    .max((a, b) -> Long.compare(a.delayMillis, b.delayMillis))
                    .ifPresent(task -> {
                        task.ran = true;
                        task.body.run();
                    });
        }
    }

    /**
     * A {@link RouteCoordinator.Transport} whose transfers finish exactly when the test says so.
     *
     * <p>This is what makes the routing races real rather than notional: a request is <em>started</em>
     * and stays in flight until the test completes it with a chosen status, which is precisely the
     * window in which Velocity would answer a competing request with {@code CONNECTION_IN_PROGRESS}.
     */
    public static final class ControllableTransport implements RouteCoordinator.Transport {

        /** One issued transfer and the future that decides how it ends. */
        public record Started(ConnectionHandle handle, Object connection,
                              RouteCoordinator.Destination destination,
                              CompletableFuture<RouteCoordinator.TransferStatus> future) {}

        public final List<Started> started = new CopyOnWriteArrayList<>();
        /** Connections this transport was asked to close, fail-closed. */
        public final List<Object> disconnected = new CopyOnWriteArrayList<>();
        /** Message keys the fail-closed disconnects carried. */
        public final List<String> disconnectReasons = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<RouteCoordinator.TransferStatus> send(
                ConnectionHandle handle, Object connection, RouteCoordinator.Destination destination) {
            CompletableFuture<RouteCoordinator.TransferStatus> future = new CompletableFuture<>();
            started.add(new Started(handle, connection, destination, future));
            return future;
        }

        /** Runs at the very start of a fail-closed disconnect, i.e. inside the terminal path. */
        public volatile Runnable beforeDisconnect = () -> { };
        /** When set, the fail-closed disconnect throws, as a dying proxy connection would. */
        public volatile RuntimeException disconnectFailure;

        @Override
        public void disconnect(ConnectionHandle handle, Object connection, String messageKey) {
            beforeDisconnect.run();
            RuntimeException failure = disconnectFailure;
            if (failure != null) {
                throw failure;
            }
            disconnected.add(connection);
            disconnectReasons.add(messageKey);
        }

        /** Every destination a transfer has been issued for, in order. */
        public List<RouteCoordinator.Destination> destinations() {
            return started.stream().map(Started::destination).toList();
        }

        /** The transfer issued most recently. */
        public Started latest() {
            if (started.isEmpty()) {
                throw new IllegalStateException("no transfer has been started");
            }
            return started.get(started.size() - 1);
        }

        /** Finishes transfer {@code index} with {@code status}, as Velocity eventually would. */
        public void finish(int index, RouteCoordinator.TransferStatus status) {
            started.get(index).future().complete(status);
        }

        /** Finishes the most recent transfer. */
        public void finishLatest(RouteCoordinator.TransferStatus status) {
            latest().future().complete(status);
        }

        /** How many transfers have not been completed yet. */
        public long inFlight() {
            return started.stream().filter(s -> !s.future().isDone()).count();
        }
    }

    /**
     * Records what the production {@link FlowResultApplier} decided to do to one player.
     *
     * <p>Only the Velocity binding is replaced - the chat line still goes to the {@link FakeConnection}
     * as a rendered component, exactly as a real {@code Player} would receive it. Whether an effect
     * runs at all is decided by production code.
     */
    public static final class RecordingEffects implements FlowResultApplier.Effects {

        private final FakeConnection player;
        private final RuntimeContext context;
        private final RouteCoordinator routes;
        private final ConnectionRegistry registry;

        /** Message keys the applier actually delivered, in order. */
        public final List<String> messageKeys = new CopyOnWriteArrayList<>();
        /** Routing actions the applier actually performed: TARGET, LIMBO or DISCONNECT. */
        public final List<String> actions = new CopyOnWriteArrayList<>();

        RecordingEffects(FakeConnection player, RuntimeContext context,
                         RouteCoordinator routes, ConnectionRegistry registry) {
            this.player = player;
            this.context = context;
            this.routes = routes;
            this.registry = registry;
        }

        @Override
        public void sendMessage(String messageKey, Object[] args) {
            messageKeys.add(messageKey);
            player.sendMessage(context.messages().component(messageKey, args));
        }

        @Override
        public void disconnect(String messageKey, Object[] args) {
            messageKeys.add(messageKey);
            actions.add("DISCONNECT");
        }

        @Override
        public CompletionStage<RouteCoordinator.RouteResult> sendToTarget() {
            actions.add("TARGET");
            return route(RouteCoordinator.Destination.TARGET);
        }

        @Override
        public CompletionStage<RouteCoordinator.RouteResult> sendToLimbo() {
            actions.add("LIMBO");
            return route(RouteCoordinator.Destination.LIMBO);
        }

        /** Hands the decision to the production coordinator, exactly as the commands do. */
        private CompletionStage<RouteCoordinator.RouteResult> route(
                RouteCoordinator.Destination destination) {
            return registry.currentFor(player.uuid(), player)
                    .map(handle -> routes.route(handle, player, destination))
                    .orElseGet(() -> CompletableFuture.completedFuture(
                            RouteCoordinator.RouteResult.CONNECTION_GONE));
        }

        /** Whether nothing at all reached this player. */
        public boolean isSilent() {
            return messageKeys.isEmpty() && actions.isEmpty();
        }
    }

    /** Recording audit log. */
    public static final class RecordingAudit implements AuthFlow.AuditLog {
        public final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void record(String action, String usernameLower, UUID uuid, String ipHash, String detail) {
            entries.add(new AuditEntry(action, usernameLower, uuid, ipHash, detail));
        }

        public List<String> actions() {
            return entries.stream().map(AuditEntry::action).toList();
        }

        public boolean has(String action) {
            return actions().contains(action);
        }
    }

    public final ConnectionRegistry registry = new ConnectionRegistry();
    public final hex.limbo.account.InMemoryAccountRepository backing = new hex.limbo.account.InMemoryAccountRepository();
    public final BlockingRepository repository = new BlockingRepository(backing);
    public final RecordingSessions sessions = new RecordingSessions();
    public final RecordingAudit audit = new RecordingAudit();
    public final RuntimeContext context;
    public final AuthService authService;
    public final PromptService prompts;
    public final AuthFlow flow;
    public final ControllableTransport transport = new ControllableTransport();
    public final ManualScheduler routeScheduler = new ManualScheduler();
    public final RouteCoordinator routes = new RouteCoordinator(
            registry, transport, routeScheduler, LoggerFactory.getLogger(RouteCoordinator.class));
    public final Map<FakeConnection, RecordingEffects> effects = new ConcurrentHashMap<>();
    public final AtomicInteger remindersScheduled = new AtomicInteger();
    public final AtomicInteger remindersCancelled = new AtomicInteger();
    public volatile PremiumResolver premiumResolver = name -> PremiumResolver.Result.notPremium();

    public AuthFlowFixture() {
        this(TestConfigs.defaultConfig());
    }

    public AuthFlowFixture(PluginConfig config) {
        this.context = new RuntimeContext(config, new MessagesConfig(messages()));
        this.authService = new AuthService(
                repository, new PasswordHasher(4), new RateLimiter(1_000, 60_000L),
                context, registry, LoggerFactory.getLogger(AuthFlowFixture.class));
        this.prompts = new PromptService(context, registry, (interval, task) -> {
            remindersScheduled.incrementAndGet();
            return remindersCancelled::incrementAndGet;
        });
        this.flow = new AuthFlow(authService, repository, sessions, audit, prompts,
                name -> premiumResolver.resolve(name), context, LoggerFactory.getLogger(AuthFlowFixture.class));
    }

    /** Every connection this fixture opened, so a test can assert they all converged. */
    public final List<ConnectionHandle> opened = new CopyOnWriteArrayList<>();

    /** Opens a connection exactly as {@code LoginListener.onLogin} does. */
    public ConnectionHandle connect(FakeConnection player) {
        ConnectionRegistry.Registration registration =
                registry.begin(player.uuid(), player.username(), player, player);
        if (registration.superseded() != null) {
            registry.releaseSuperseded(registration.superseded());
            prompts.endConnection(registration.superseded());
            routes.endConnection(registration.superseded());
        }
        opened.add(registration.handle());
        return registration.handle();
    }

    /** Ends a connection exactly as {@code DisconnectListener} does. */
    public void disconnect(FakeConnection player) {
        registry.endIfCurrent(player.uuid(), player).ifPresent(handle -> {
            prompts.endConnection(handle);
            routes.endConnection(handle);
        });
    }

    /** Everything the applier has delivered to this player so far. */
    public RecordingEffects effectsFor(FakeConnection player) {
        return effects.computeIfAbsent(player, p -> new RecordingEffects(p, context, routes, registry));
    }

    /**
     * Runs a flow the way {@code FlowCommandSupport.runAsync} does - through the production
     * {@link FlowResultApplier#execute}, so the ordered failure reporting is the shipped one.
     */
    public FlowResultApplier.Application execute(
            String label, ConnectionHandle handle, FakeConnection player,
            java.util.function.Supplier<AuthFlow.Result> flow) {
        return FlowResultApplier.execute(registry, handle, player, effectsFor(player), label,
                LoggerFactory.getLogger(AuthFlowFixture.class), flow);
    }

    /** Confirms an arrival, as {@code ServerConnectListener.onServerConnected} does. */
    public boolean arriveAt(ConnectionHandle handle, FakeConnection player, RouteCoordinator.Destination where) {
        return routes.onArrived(handle, player, where);
    }

    /**
     * Applies a flow result through the production {@link FlowResultApplier}, exactly as
     * {@code FlowCommandSupport} does for a real {@code Player}. Only the Velocity calls are
     * substituted; the decision of whether the result may still be applied is production code.
     *
     * @return what the applier decided, and how the transfer it asked for ends
     */
    public FlowResultApplier.Application apply(AuthFlow.Result result, FakeConnection player) {
        return FlowResultApplier.apply(registry, result, player, effectsFor(player));
    }

    /** Runs the join pipeline for a plain cracked player. */
    public AuthFlow.JoinResult joinCracked(ConnectionHandle handle) {
        return flow.resolveJoin(handle, new AuthFlow.JoinRequest(false, false, "ip-hash"));
    }

    /** Registers an account outside the test's window of interest. */
    public void seedAccount(String username, String password) {
        FakeConnection setup = FakeConnection.of(username + "-seed");
        ConnectionHandle handle = registry.begin(
                UUID.nameUUIDFromBytes(("u:" + username).getBytes()), username, setup, setup).handle();
        joinCracked(handle);
        flow.register(handle, password, password);
        registry.end(handle);
        prompts.endConnection(handle);
        reset();
    }

    /** Clears everything the recorders captured, so a test asserts only on its own window. */
    public void reset() {
        sessions.created.clear();
        sessions.invalidated.clear();
        sessions.live.clear();
        audit.entries.clear();
        repository.writes.clear();
        effects.clear();
    }

    /** How many times the named repository write was executed. */
    public long writeCount(String write) {
        return repository.writes.stream().filter(write::equals).count();
    }

    private static Map<String, String> messages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prompts.login.bossbar", "&6Hex &8Zaloguj się");
        m.put("prompts.login.title", "&6&lHEX");
        m.put("prompts.login.subtitle", "&7Zaloguj się: &f/login <hasło>");
        m.put("prompts.login.chat", "&7Musisz się zalogować.");
        m.put("prompts.register.bossbar", "&6Hex &8Zarejestruj się");
        m.put("prompts.register.title", "&6&lHEX");
        m.put("prompts.register.subtitle", "&7Zarejestruj się: &f/register <hasło> <hasło>");
        m.put("prompts.register.chat", "&7Nie masz jeszcze konta.");
        m.put("prompts.success.chat", "&aZalogowano pomyślnie.");
        m.put("prompts.success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.success.subtitle", "&7Witamy na &6Hex&7!");
        m.put("prompts.session-success.chat", "&aZalogowano automatycznie.");
        m.put("prompts.session-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.session-success.subtitle", "&7Zalogowano przez &eaktywną sesję&7.");
        m.put("prompts.premium-success.chat", "&aZalogowano pomyślnie.");
        m.put("prompts.premium-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-success.subtitle", "&7Zalogowano przez konto &6premium&7.");
        m.put("prompts.premium-skip.chat", "&aZalogowano pomyślnie.");
        m.put("prompts.premium-skip.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-skip.subtitle", "&7Poczekalnia pominięta.");
        m.put("login.success", "&aLogowanie zakończone pomyślnie.");
        m.put("register.success", "&aRejestracja zakończona pomyślnie.");
        m.put("logout.success", "&aWylogowano.");
        m.put("changepassword.success", "&aHasło zostało zmienione.");
        m.put("changepassword.failed", "&cNie udało się zmienić hasła.");
        m.put("premium.requested", "&aZgłoszono prośbę o migrację.");
        return m;
    }
}
