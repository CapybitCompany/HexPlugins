package hex.limbo.auth;

import hex.limbo.prompt.AuthReason;
import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link AuthFlow}, {@link AuthService}, {@link hex.limbo.prompt.PromptService} and
 * {@link ConnectionRegistry} through the orderings a fast reconnect can produce.
 *
 * <p>Everything here goes through {@link AuthFlowFixture}, which wires the production services
 * together and replaces only the edges they already abstract (session store, audit log, repository,
 * Mojang resolver). There is no re-implementation of the listener/command sequence, so the ordering
 * under test cannot drift away from the ordering the proxy runs. The two genuinely Velocity-shaped
 * decisions - "does this timeout still kick anybody" and "is this socket allowed to act" - are
 * exercised through the production predicates {@link ConnectionRegistry#shouldTimeOut} and
 * {@link ConnectionRegistry#isAuthenticatedConnection}.
 *
 * <p>Interleavings are forced with {@link CountDownLatch}es rather than hoped for.
 */
class ConnectionRaceTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** The production timeout decision, as {@code LoginListener} evaluates it. */
    private boolean timeoutWouldKick(ConnectionHandle handle, FakeConnection onlinePlayer) {
        return fixture.registry.shouldTimeOut(handle, onlinePlayer);
    }

    // ------------------------------------------- stale worker after a fast reconnect

    @Test
    void oldLoginWorkerStartingAfterAReconnectLeavesTheNewConnectionUnauthenticated() {
        fixture.seedAccount("Alice", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Alice".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Alice");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        assertSame(oldHandle, fixture.registry.currentFor(uuid, first).orElseThrow());

        // Player drops and comes straight back on a new socket.
        fixture.disconnect(first);
        FakeConnection second = new FakeConnection(uuid, "Alice");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        assertNotSame(oldHandle, newHandle);
        fixture.reset();

        // Only now does the first connection's worker get scheduled and finish.
        AuthFlow.Result result = fixture.flow.login(oldHandle, "verylongpw");

        assertEquals(Optional.empty(), result.messageKey());
        assertEquals(AuthFlow.Routing.NONE, result.routing());
        assertFalse(newHandle.isAuthenticated(), "the new connection must stay unauthenticated");
        assertFalse(oldHandle.isAuthenticated(), "the dead connection must not be authenticated either");
        assertTrue(fixture.sessions.created.isEmpty(), "no session");
        assertTrue(fixture.audit.entries.isEmpty(), "no audit: " + fixture.audit.actions());
        assertTrue(fixture.prompts.pendingLobbyGreeting(newHandle).isEmpty(), "no greeting");
        assertTrue(second.messages.isEmpty(), "the new connection must not be told it logged in");
    }

    @Test
    void oldRegisterWorkerFinishingAfterAReconnectCommitsNothing() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Bruno".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Bruno");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        fixture.disconnect(first);
        FakeConnection second = new FakeConnection(uuid, "Bruno");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        fixture.reset();

        AuthFlow.Result result = fixture.flow.register(oldHandle, "verylongpw", "verylongpw");

        assertEquals(Optional.empty(), result.messageKey());
        assertFalse(newHandle.isAuthenticated());
        assertTrue(fixture.backing.findByUsername("bruno").isEmpty(),
                "a stale registration must not create the account row");
        assertTrue(fixture.audit.entries.isEmpty());
        assertTrue(fixture.prompts.pendingLobbyGreeting(newHandle).isEmpty());
    }

    @Test
    void staleWorkerCannotReplaceTheGreetingOfANewSessionConnection() {
        fixture.seedAccount("Cara", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Cara".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Cara");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        fixture.disconnect(first);

        // The reconnect gets in via a valid session and queues its own greeting.
        fixture.sessions.sessionValid = true;
        FakeConnection second = new FakeConnection(uuid, "Cara");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        assertEquals(Optional.of(AuthReason.SESSION), fixture.prompts.pendingLobbyGreeting(newHandle));

        // The straggler from the previous socket lands afterwards.
        fixture.flow.login(oldHandle, "verylongpw");

        assertEquals(Optional.of(AuthReason.SESSION), fixture.prompts.pendingLobbyGreeting(newHandle),
                "the session greeting must survive the stale MANUAL_LOGIN callback");
        fixture.prompts.onArrivedAtTarget(newHandle);
        assertEquals("Zalogowano przez aktywną sesję.",
                FakeConnection.plain(second.titles.get(0).subtitle()));
    }

    // ------------------------------------------------ late disconnect of an old socket

    @Test
    void aLateDisconnectOfTheOldSocketLeavesTheNewConnectionFullyIntact() {
        fixture.seedAccount("Dora", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Dora".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Dora");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        // Connection B starts before A's DisconnectEvent has been delivered.
        FakeConnection second = new FakeConnection(uuid, "Dora");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        fixture.prompts.showLimboPrompt(newHandle, AuthState.Stage.AWAITING_LOGIN);
        assertTrue(fixture.prompts.hasActivePrompt(newHandle));
        assertEquals(1, second.shownBars.size());

        // ...and only now does A's disconnect arrive.
        fixture.disconnect(first);

        assertTrue(fixture.registry.isCurrent(newHandle), "the live connection must survive");
        assertEquals(1, fixture.registry.size());
        assertTrue(newHandle.authState().isPresent(), "auth state must survive");
        assertTrue(fixture.prompts.hasActivePrompt(newHandle), "prompt state must survive");
        assertEquals(0, second.hiddenBars.size(), "the live BossBar must not be hidden");

        // The new connection can still authenticate normally afterwards.
        AuthFlow.Result result = fixture.flow.login(newHandle, "verylongpw");
        assertEquals(Optional.of("login.success"), result.messageKey());
        assertEquals(AuthFlow.Routing.TARGET, result.routing());
        fixture.prompts.onArrivedAtTarget(newHandle);
        assertEquals("Witamy na Hex!", FakeConnection.plain(
                second.titles.get(second.titles.size() - 1).subtitle()));
    }

    @Test
    void aLateDisconnectDoesNotDisarmTheNewConnectionsLoginTimeout() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Egon".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Egon");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        FakeConnection second = new FakeConnection(uuid, "Egon");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        fixture.disconnect(first);

        assertTrue(timeoutWouldKick(newHandle, second),
                "the live connection's own timeout must still be able to kick it");
        assertFalse(timeoutWouldKick(oldHandle, second),
                "but not through the dead connection's handle");
    }

    @Test
    void anOldLoginTimeoutFiringAfterAReconnectDoesNotKickTheNewPlayer() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Frida".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Frida");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        fixture.disconnect(first);
        FakeConnection second = new FakeConnection(uuid, "Frida");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        assertFalse(timeoutWouldKick(oldHandle, second),
                "a timeout armed by a dead socket must never kick the reconnect that took the UUID over");
        assertTrue(fixture.registry.isCurrent(newHandle));
    }

    @Test
    void anOldTimeoutIsAlsoHarmlessWhenTheReconnectRacedAheadOfTheDisconnect() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Gustav".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Gustav");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        // No disconnect yet: B supersedes A.
        FakeConnection second = new FakeConnection(uuid, "Gustav");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        assertFalse(timeoutWouldKick(oldHandle, second), "a superseded timeout must not kick anybody");
        assertFalse(timeoutWouldKick(oldHandle, first));
        assertTrue(fixture.registry.isCurrent(newHandle));
    }

    @Test
    void anAuthenticatedConnectionIsNeverTimedOut() {
        fixture.seedAccount("Hania", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Hania".getBytes()), "Hania");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        assertTrue(timeoutWouldKick(handle, player), "precondition: unauthenticated players do time out");

        fixture.flow.login(handle, "verylongpw");

        assertFalse(timeoutWouldKick(handle, player), "an authenticated player must not be kicked");
    }

    // ------------------------------------------------------ disconnect during the flow

    @Test
    void disconnectDuringTheJoinLeavesNoState() {
        FakeConnection player = FakeConnection.of("Hilde");
        ConnectionHandle handle = fixture.connect(player);

        // The DB lookup is still running when the player gives up and closes the client.
        fixture.disconnect(player);

        AuthFlow.JoinResult result = fixture.joinCracked(handle);

        assertFalse(result.authenticated());
        assertTrue(handle.authState().isEmpty(), "no auth state may be attached");
        assertEquals(0, fixture.registry.size());
        assertEquals(0, fixture.prompts.trackedDisplays());
        assertTrue(fixture.audit.entries.isEmpty());
        assertFalse(timeoutWouldKick(handle, player));
    }

    @Test
    void aLateLimboPromptAfterDisconnectCreatesNothing() {
        FakeConnection player = FakeConnection.of("Igor");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        fixture.disconnect(player);

        // The limbo's ServerConnectedEvent was already in flight when the player left.
        assertFalse(fixture.prompts.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN));

        assertTrue(player.shownBars.isEmpty(), "no BossBar may be created for a dead connection");
        assertEquals(0, fixture.remindersScheduled.get(), "no reminder task may be scheduled");
        assertEquals(0, fixture.prompts.trackedDisplays(), "no display entry may be created");
        assertEquals(0, fixture.registry.size(), "no connection entry may be resurrected");

        assertEquals(Optional.empty(), fixture.prompts.onArrivedAtTarget(handle));
        assertTrue(player.titles.isEmpty());
    }

    @Test
    void aLateArrivalFromAnOldSocketCannotConsumeTheNewGreeting() {
        fixture.seedAccount("Jana", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Jana".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Jana");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        fixture.disconnect(first);

        fixture.sessions.sessionValid = true;
        FakeConnection second = new FakeConnection(uuid, "Jana");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        // Old socket's lobby event lands after the reconnect queued its greeting.
        assertEquals(Optional.empty(), fixture.prompts.onArrivedAtTarget(oldHandle));

        assertTrue(first.titles.isEmpty(), "the dead socket must be shown nothing");
        assertEquals(Optional.of(AuthReason.SESSION), fixture.prompts.pendingLobbyGreeting(newHandle),
                "the live connection's greeting must still be pending");
    }

    // ------------------------------------------------------ forced concurrent orderings

    @Test
    void concurrentDisconnectAndLoginCommitNeverLeaveState() throws InterruptedException {
        for (int run = 0; run < 40; run++) {
            AuthFlowFixture f = new AuthFlowFixture();
            f.seedAccount("Kai", "verylongpw");
            FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Kai".getBytes()), "Kai");
            ConnectionHandle handle = f.connect(player);
            f.joinCracked(handle);
            f.prompts.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
            f.reset();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.execute(() -> {
                    await(start);
                    f.disconnect(player);
                    done.countDown();
                });
                pool.execute(() -> {
                    await(start);
                    result.set(f.flow.login(handle, "verylongpw"));
                    done.countDown();
                });
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }

            assertEquals(0, f.registry.size(), "the disconnect must always win the cleanup");
            assertEquals(0, f.prompts.trackedDisplays());
            assertEquals(0, f.registry.commitsInFlight(), "no lease may leak");
            assertTrue(f.prompts.pendingLobbyGreeting(handle).isEmpty());
            assertEquals(f.remindersScheduled.get(), f.remindersCancelled.get(),
                    "every scheduled reminder must have been cancelled");
            // The login either committed fully or not at all.
            boolean committed = handle.isAuthenticated();
            assertEquals(committed, f.audit.has("LOGIN"),
                    "run " + run + ": the success audit must track the commit exactly");
            assertEquals(committed, !f.sessions.created.isEmpty(),
                    "run " + run + ": the session must track the commit exactly");
            // A later lobby arrival is silent either way.
            assertEquals(Optional.empty(), f.prompts.onArrivedAtTarget(handle));
        }
    }

    @Test
    void aWorkerHeldUntilAfterTheReconnectIsRejected() throws InterruptedException {
        fixture.seedAccount("Lena", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Lena".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Lena");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        fixture.reset();

        CountDownLatch reconnected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            await(reconnected); // stands in for a slow BCrypt verify
            result.set(fixture.flow.login(oldHandle, "verylongpw"));
            finished.countDown();
        }, "slow-login");
        worker.start();

        fixture.disconnect(first);
        FakeConnection second = new FakeConnection(uuid, "Lena");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        reconnected.countDown();

        assertTrue(finished.await(10, TimeUnit.SECONDS));
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(Optional.empty(), result.get().messageKey());
        assertFalse(newHandle.isAuthenticated(), "the new connection must still require its own /login");
        assertTrue(fixture.audit.entries.isEmpty(), "no side effects: " + fixture.audit.actions());
        assertTrue(fixture.sessions.created.isEmpty());
    }

    // ------------------------------------------------------------------ churn

    @Test
    void multiPlayerChurnLeavesNoConnectionsDisplaysOrLeases() throws InterruptedException {
        int players = 150;
        List<FakeConnection> sockets = new ArrayList<>();
        Map<FakeConnection, ConnectionHandle> handles = new HashMap<>();
        for (int i = 0; i < players; i++) {
            FakeConnection player = FakeConnection.of("Churn-" + i);
            ConnectionHandle handle = fixture.connect(player);
            fixture.joinCracked(handle);
            fixture.prompts.showLimboPrompt(handle, AuthState.Stage.UNREGISTERED);
            sockets.add(player);
            handles.put(player, handle);
        }
        assertEquals(players, fixture.registry.size());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(players * 2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (FakeConnection player : sockets) {
                ConnectionHandle handle = handles.get(player);
                pool.execute(() -> {
                    await(start);
                    fixture.flow.register(handle, "verylongpw", "verylongpw");
                    done.countDown();
                });
                pool.execute(() -> {
                    await(start);
                    fixture.disconnect(player);
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, fixture.registry.size(), "every connection must be released");
        assertEquals(0, fixture.prompts.trackedDisplays(), "every display must be released");
        assertEquals(0, fixture.registry.commitsInFlight(), "every lease must be released");
        assertEquals(0, fixture.registry.commitSlotsTracked(),
                "and every per-UUID commit slot must be freed, so the structure stays bounded");
        assertEquals(fixture.remindersScheduled.get(), fixture.remindersCancelled.get(),
                "every reminder task must have been cancelled");
        for (FakeConnection player : sockets) {
            ConnectionHandle handle = handles.get(player);
            assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
            assertFalse(fixture.prompts.hasActivePrompt(handle));
            assertFalse(fixture.registry.isAuthenticatedConnection(player.uuid(), player),
                    "and no departed socket is left able to act");
        }
    }
}
