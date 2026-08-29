package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks each authentication journey in the exact order the Velocity listeners call
 * {@link PromptService}, so the timing contract is pinned down end to end:
 *
 * <pre>
 *   LoginEvent (sync)     -&gt; registry.begin(...)             (every tracked player)
 *   LoginEvent (async)    -&gt; markAuthenticated(handle, ...)  (session / premium / admin bypass)
 *   ServerConnectedEvent  -&gt; showLimboPrompt(handle, ...)    (unauthenticated, limbo backend)
 *   /login | /register    -&gt; onAuthenticated(handle, ...)    (still in the limbo)
 *   ServerConnectedEvent  -&gt; onArrivedAtTarget(handle)       (target server confirmed)
 *   DisconnectEvent       -&gt; endIfCurrent + endConnection
 * </pre>
 */
class AuthGreetingFlowTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();

    private static Map<String, String> messages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prompts.login.bossbar", "&6Hex &8» &7Zaloguj się: &f/login <hasło>");
        m.put("prompts.login.title", "&6&lHEX");
        m.put("prompts.login.subtitle", "&7Zaloguj się: &f/login <hasło>");
        m.put("prompts.login.chat", "&8» &7Musisz się zalogować. Użyj: &f/login <hasło>");
        m.put("prompts.register.bossbar", "&6Hex &8» &7Zarejestruj się: &f/register <hasło> <hasło>");
        m.put("prompts.register.title", "&6&lHEX");
        m.put("prompts.register.subtitle", "&7Zarejestruj się: &f/register <hasło> <hasło>");
        m.put("prompts.register.chat", "&8» &7Nie masz jeszcze konta. Użyj: &f/register <hasło> <hasło>");
        m.put("prompts.success.chat", "&8» &aZalogowano pomyślnie.");
        m.put("prompts.success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.success.subtitle", "&7Witamy na &6Hex&7!");
        m.put("prompts.session-success.chat", "&8» &aZalogowano automatycznie.");
        m.put("prompts.session-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.session-success.subtitle", "&7Zalogowano przez &eaktywną sesję&7.");
        m.put("prompts.premium-success.chat", "&8» &aZalogowano pomyślnie.");
        m.put("prompts.premium-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-success.subtitle", "&7Zalogowano przez konto &6premium&7.");
        m.put("prompts.premium-skip.chat", "&8» &aZalogowano pomyślnie.");
        m.put("prompts.premium-skip.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-skip.subtitle", "&7Poczekalnia pominięta.");
        return m;
    }

    private RuntimeContext context() {
        return new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(messages()));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void crackedPlayerLoginJourney() {
        FakeConnection player = FakeConnection.of("Cracked");
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(context(), connections, scheduler);
        ConnectionHandle handle = player.connect(connections);

        // 1. Lands in the limbo with an existing account.
        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        assertEquals("HEX", plain(player.titles.get(0).title()));
        assertEquals("Zaloguj się: /login <hasło>", plain(player.titles.get(0).subtitle()));

        // 2. /login succeeds - the prompt goes away, but nothing new is shown yet.
        assertTrue(service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN));
        assertEquals(1, player.titles.size(), "success title must wait for the lobby");
        assertEquals(1, player.hiddenBars.size());
        assertEquals(1, scheduler.cancelCount);

        // 3. Only once the lobby connection is confirmed does the success title appear.
        service.onArrivedAtTarget(handle);
        assertEquals(2, player.titles.size());
        assertEquals("Zalogowano pomyślnie!", plain(player.titles.get(1).title()));
        assertEquals("Witamy na Hex!", plain(player.titles.get(1).subtitle()));
    }

    @Test
    void newPlayerRegisterJourney() {
        FakeConnection player = FakeConnection.of("Newbie");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.UNREGISTERED);
        assertEquals("HEX", plain(player.titles.get(0).title()));
        assertEquals("Zarejestruj się: /register <hasło> <hasło>", plain(player.titles.get(0).subtitle()));

        assertTrue(service.onAuthenticated(handle, AuthReason.REGISTER));
        assertEquals(1, player.titles.size());

        service.onArrivedAtTarget(handle);
        assertEquals("Zalogowano pomyślnie!", plain(player.titles.get(1).title()));
        assertEquals("Witamy na Hex!", plain(player.titles.get(1).subtitle()));
    }

    @Test
    void sessionPlayerNeverSeesTheLimboPrompt() {
        FakeConnection player = FakeConnection.of("Session");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        // LoginEvent auto-authenticates via a valid session, so the player is routed straight to
        // the lobby and showLimboPrompt is never called for them.
        service.markAuthenticated(handle, AuthReason.SESSION);
        assertTrue(player.shownBars.isEmpty(), "session players must not get a BossBar");

        service.onArrivedAtTarget(handle);

        assertEquals(1, player.titles.size());
        assertEquals("Zalogowano pomyślnie!", plain(player.titles.get(0).title()));
        assertEquals("Zalogowano przez aktywną sesję.", plain(player.titles.get(0).subtitle()));
    }

    @Test
    void premiumPlayerGetsAPremiumTitleAndNoLimboPrompt() {
        FakeConnection player = FakeConnection.of("Premium");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.markAuthenticated(handle, AuthReason.PREMIUM);
        service.onArrivedAtTarget(handle);

        assertTrue(player.shownBars.isEmpty(), "premium players must not get a BossBar");
        assertEquals(1, player.titles.size());
        assertEquals("Zalogowano pomyślnie!", plain(player.titles.get(0).title()));
        assertEquals("Zalogowano przez konto premium.", plain(player.titles.get(0).subtitle()));
    }

    @Test
    void adminBypassPlayerKeepsTheLimboSkippedWording() {
        FakeConnection player = FakeConnection.of("Staff");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.markAuthenticated(handle, AuthReason.ADMIN_BYPASS);
        service.onArrivedAtTarget(handle);

        assertTrue(player.shownBars.isEmpty());
        assertEquals("Poczekalnia pominięta.", plain(player.titles.get(0).subtitle()));
    }

    @Test
    void logoutDropsThePendingGreetingAndRestoresTheLimboPrompt() {
        FakeConnection player = FakeConnection.of("Logout");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        service.onArrivedAtTarget(handle);
        int titlesAfterLogin = player.titles.size();

        // /logout: tear the prompt down but keep the connection, then route back to the limbo.
        service.clearPrompt(handle);
        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);

        assertEquals(titlesAfterLogin + 1, player.titles.size(), "the limbo prompt must come back");
        assertEquals("HEX", plain(player.titles.get(player.titles.size() - 1).title()));
        assertTrue(service.pendingLobbyGreeting(handle).isEmpty());
        assertTrue(connections.isCurrent(handle),
                "/logout must not retire the connection - the player is still online");

        // ...and logging in again within the same connection works with the same handle.
        assertTrue(service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN));
        service.onArrivedAtTarget(handle);
        assertEquals("Zalogowano pomyślnie!", plain(player.titles.get(player.titles.size() - 1).title()));
    }

    @Test
    void failedTransferLeavesNoStaleGreetingAfterDisconnect() {
        FakeConnection player = FakeConnection.of("Dropped");
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        // The transfer to the lobby never completes; the player drops out instead.
        connections.endIfCurrent(handle.uuid(), player);
        service.endConnection(handle);

        assertFalse(service.hasActivePrompt(handle));
        assertTrue(service.pendingLobbyGreeting(handle).isEmpty(), "no pending greeting may leak");
        assertEquals(0, connections.size(), "the connection must be gone");
        assertEquals(0, service.trackedDisplays());

        // A brand new connection for the same UUID starts from a clean slate.
        FakeConnection reconnect = new FakeConnection(player.uuid(), player.username());
        ConnectionHandle fresh = reconnect.connect(connections);
        service.showLimboPrompt(fresh, AuthState.Stage.AWAITING_LOGIN);
        assertEquals(1, reconnect.titles.size());
        assertEquals(1, reconnect.shownBars.size());
    }

    @Test
    void manyPlayersDoNotLeakState() {
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        List<FakeConnection> players = new ArrayList<>();
        List<ConnectionHandle> handles = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            FakeConnection player = FakeConnection.of("Bulk-" + i);
            ConnectionHandle handle = player.connect(connections);
            players.add(player);
            handles.add(handle);
            service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
            service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        }
        assertEquals(handles.size(), connections.size());
        for (ConnectionHandle handle : handles) {
            service.onArrivedAtTarget(handle);
        }
        for (int i = 0; i < handles.size(); i++) {
            ConnectionHandle handle = handles.get(i);
            assertFalse(service.hasActivePrompt(handle));
            assertTrue(service.pendingLobbyGreeting(handle).isEmpty());
            connections.endIfCurrent(handle.uuid(), players.get(i));
            service.endConnection(handle);
        }
        assertEquals(0, connections.size(), "every connection must be released");
        assertEquals(0, service.trackedDisplays(), "every display must be released");
    }

    @Test
    void concurrentTargetArrivalsProduceExactlyOneGreeting() throws InterruptedException {
        PromptService service = new PromptService(context(), connections, new FakeScheduler());
        FakeConnection player = FakeConnection.of("Concurrent");
        ConnectionHandle handle = player.connect(connections);
        service.markAuthenticated(handle, AuthReason.SESSION);

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger greetings = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (service.onArrivedAtTarget(handle).isPresent()) {
                            greetings.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "workers must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, greetings.get(), "only one thread may win the pending greeting");
        assertEquals(1, player.titles.size());
    }

    @Test
    void reloadedMessagesTakeEffectOnTheNextGreeting() {
        RuntimeContext context = context();
        FakeConnection player = FakeConnection.of("Reload");
        PromptService service = new PromptService(context, connections, new FakeScheduler());

        Map<String, String> reloaded = new LinkedHashMap<>(messages());
        reloaded.put("prompts.session-success.subtitle", "&7Wrócono przez &bzapamiętaną sesję&7.");
        context.update(context.config(), new MessagesConfig(reloaded));

        ConnectionHandle handle = player.connect(connections);
        service.markAuthenticated(handle, AuthReason.SESSION);
        service.onArrivedAtTarget(handle);

        assertEquals("Wrócono przez zapamiętaną sesję.", plain(player.titles.get(0).subtitle()));
    }

    private static final class FakeScheduler implements PromptService.Scheduler {
        int scheduledCount = 0;
        int cancelCount = 0;

        @Override
        public PromptService.PromptTask scheduleRepeating(long intervalSeconds, Runnable task) {
            scheduledCount++;
            return () -> cancelCount++;
        }
    }
}
