package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operations on one account have to form a single total order, and the operation that acquires the
 * UUID's commit slot later has to <em>win</em>.
 *
 * <p>A lease acquired around the write alone is not enough for that. It leaves two holes, and this
 * class closes both:
 *
 * <ul>
 *     <li>A {@code /logout} could slip between a {@code /login}'s state flip and the session row and
 *     greeting that belong to it, so the logout demoted the player and the login then re-authenticated
 *     them, re-created the session and left a success greeting queued.</li>
 *     <li>Two connections of the same account could each hold their own lease at the same time -
 *     different handles, different tokens - decide independently that the account does not exist yet,
 *     and both create it.</li>
 * </ul>
 *
 * <p>Every interleaving here is forced, never hoped for. {@link AuthFlowFixture.BlockingRepository}
 * holds a flow inside the repository call it is about to make, and
 * {@link ConnectionRegistry#setCommitQueuedObserver} fires the instant a second operation parks
 * behind that one - so "the second operation is waiting" is a latch, not a sleep.
 *
 * <p>Everything runs through the production {@link AuthFlow}, so the order under test is the order
 * the proxy runs.
 */
class AuthCommitOrderingTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    private Thread run(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.start();
        return thread;
    }

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

    private static void join(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(thread.isAlive(), thread.getName() + " never finished");
        }
    }

    private static UUID uuidOf(String username) {
        return UUID.nameUUIDFromBytes(("u:" + username).getBytes());
    }

    /** Arms the observer that fires the moment an operation queues behind another for its UUID. */
    private CountDownLatch queueLatch() {
        CountDownLatch queued = new CountDownLatch(1);
        fixture.registry.setCommitQueuedObserver(handle -> queued.countDown());
        return queued;
    }

    // ------------------------------------------------- a later logout beats a running login

    /**
     * The headline case. {@code /login} is held inside {@code recordSuccessfulLogin} with its
     * section open; {@code /logout} arrives afterwards and has to wait. When the dust settles the
     * <em>logout</em> is the last thing that happened: demoted, session gone, greeting gone.
     */
    @Test
    void aLogoutIssuedDuringALoginCommitWinsTheOrdering() throws InterruptedException {
        fixture.seedAccount("Nina", "verylongpw");
        UUID uuid = uuidOf("Nina");
        FakeConnection player = new FakeConnection(uuid, "Nina");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch atWrite = fixture.repository.pauseAt("recordSuccessfulLogin");
        AtomicReference<AuthFlow.Result> loginResult = new AtomicReference<>();
        Thread login = run("login", () -> loginResult.set(fixture.flow.login(handle, "verylongpw")));
        await(atWrite); // the login owns the UUID and is inside its write

        CountDownLatch logoutQueued = queueLatch();
        AtomicReference<AuthFlow.Result> logoutResult = new AtomicReference<>();
        Thread logout = run("logout", () -> logoutResult.set(fixture.flow.logout(handle)));
        await(logoutQueued); // ...and the logout is provably parked behind it

        assertEquals(1, fixture.registry.commitsInFlight(),
                "a queued logout must not hold a second lease for the same UUID");

        fixture.repository.resume("recordSuccessfulLogin");
        join(login, logout);

        assertEquals(AuthState.Stage.AWAITING_LOGIN, handle.authState().orElseThrow().stage(),
                "the later logout decides the final state");
        assertFalse(handle.isAuthenticated());
        assertFalse(fixture.sessions.hasLiveSession(uuid),
                "the login's session row must not survive the logout that came after it");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty(),
                "no success greeting may be left queued for a player who logged out");
        assertEquals(List.of("LOGIN", "LOGOUT"), fixture.audit.actions(),
                "LOGOUT is the last logical operation");
        assertEquals(Optional.of("logout.success"), logoutResult.get().messageKey());
        assertEquals(AuthFlow.Routing.LIMBO, logoutResult.get().routing());
        assertEquals(Optional.of("login.success"), loginResult.get().messageKey(),
                "the login itself did happen and reports so");
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /** The same ordering through the lifecycle {@code /hexlimbo forcelogout} actually uses. */
    @Test
    void anAdminForceLogoutIssuedDuringALoginCommitWinsTheOrdering() throws InterruptedException {
        fixture.seedAccount("Mirek", "verylongpw");
        UUID uuid = uuidOf("Mirek");
        FakeConnection player = new FakeConnection(uuid, "Mirek");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch atWrite = fixture.repository.pauseAt("recordSuccessfulLogin");
        Thread login = run("login", () -> fixture.flow.login(handle, "verylongpw"));
        await(atWrite);

        CountDownLatch forceQueued = queueLatch();
        AtomicReference<AuthService.LogoutOutcome> outcome = new AtomicReference<>();
        Thread force = run("forcelogout", () -> outcome.set(fixture.flow.forceLogout(handle).outcome()));
        await(forceQueued);

        assertEquals(1, fixture.registry.commitsInFlight());

        fixture.repository.resume("recordSuccessfulLogin");
        join(login, force);

        assertEquals(AuthService.LogoutOutcome.SUCCESS, outcome.get());
        assertEquals(AuthState.Stage.AWAITING_LOGIN, handle.authState().orElseThrow().stage());
        assertFalse(fixture.sessions.hasLiveSession(uuid),
                "a forced logout must not leave the login's session behind either");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
        assertEquals(List.of("LOGIN"), fixture.audit.actions(),
                "forceLogout writes no LOGOUT entry: the staff command records ADMIN_FORCE_LOGOUT itself");
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    // ------------------------------------- a second connection for the same UUID must queue

    /**
     * Connection A holds its registration lease right before {@code repository.create} when
     * connection B takes the same UUID over and runs its own join plus {@code /register}.
     *
     * <p>B must never get a lease of its own while A holds one, and once A is done B has to
     * re-evaluate: the account now exists, so B registers nothing, hits no duplicate key, and keeps
     * its own {@link AuthState} rather than aliasing A's.
     */
    @Test
    void aSecondConnectionQueuesBehindTheFirstAndThenReevaluatesTheAccount() throws InterruptedException {
        UUID uuid = uuidOf("Olga");
        FakeConnection first = new FakeConnection(uuid, "Olga");
        ConnectionHandle a = fixture.connect(first);
        fixture.joinCracked(a);

        CountDownLatch atCreate = fixture.repository.pauseAt("create");
        AtomicReference<AuthFlow.Result> resultA = new AtomicReference<>();
        Thread threadA = run("register-A", () -> resultA.set(fixture.flow.register(a, "verylongpw", "verylongpw")));
        await(atCreate);
        assertEquals(1, fixture.registry.commitsInFlight(), "A holds the only lease");

        // B takes the UUID over. Registering the socket is immediate and never waits...
        FakeConnection second = new FakeConnection(uuid, "Olga");
        ConnectionHandle b = fixture.connect(second);
        assertTrue(fixture.registry.isCurrent(b), "the new socket is addressable straight away");

        // ...but everything B does asynchronously queues behind A's commit.
        CountDownLatch bQueued = queueLatch();
        AtomicReference<AuthFlow.JoinResult> joinB = new AtomicReference<>();
        AtomicReference<AuthFlow.Result> registerB = new AtomicReference<>();
        Thread threadB = run("join-register-B", () -> {
            joinB.set(fixture.joinCracked(b));
            registerB.set(fixture.flow.register(b, "verylongpw", "verylongpw"));
        });
        await(bQueued);

        assertEquals(1, fixture.registry.commitsInFlight(),
                "two active leases for one UUID would be exactly the bug under test");

        fixture.repository.resume("create");
        join(threadA, threadB);

        assertEquals(1, fixture.writeCount("create"), "the account must be created exactly once");
        assertEquals(Optional.of("register.already-registered"), registerB.get().messageKey(),
                "B re-read the finished account state instead of racing A into a duplicate key");
        assertFalse(joinB.get().denied(), "B's join itself is fine, it just is not a fresh account any more");
        assertEquals(AuthState.Stage.AWAITING_LOGIN, b.authState().orElseThrow().stage(),
                "B has to log in to the account A created");
        assertFalse(b.isAuthenticated(), "no AuthState aliasing: A's commit is not B's");
        assertNotSame(a.authState().orElseThrow(), b.authState().orElseThrow(),
                "the two connections must not share an AuthState object");
        assertTrue(a.isAuthenticated(), "A's own commit still stands - it won the section first");
        assertEquals(Optional.empty(), resultA.get().messageKey(), "but A's socket is gone, so it is told nothing");
        assertTrue(second.messages.isEmpty(), "and B is never sent A's confirmation");
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /** A disconnect must stay immediate even while that UUID's commit slot is held. */
    @Test
    void aDisconnectIsNeverBlockedByACommitInFlight() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Pawel");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        CountDownLatch atCreate = fixture.repository.pauseAt("create");
        Thread worker = run("register", () -> fixture.flow.register(handle, "verylongpw", "verylongpw"));
        await(atCreate);

        // Runs on this thread while the worker still owns the slot. If the disconnect path took the
        // slot too, this call would park and the test could never reach the resume below.
        fixture.disconnect(player);
        assertEquals(0, fixture.registry.size(), "the disconnect took effect immediately");

        fixture.repository.resume("create");
        join(worker);
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    // ------------------------------------------------------------------ different UUIDs

    /** Ordering is per account: two different players must still commit at the same time. */
    @Test
    void commitsForDifferentUuidsRunInParallel() throws InterruptedException {
        FakeConnection one = FakeConnection.of("Rysiek");
        FakeConnection two = FakeConnection.of("Stefan");
        ConnectionHandle handleOne = fixture.connect(one);
        ConnectionHandle handleTwo = fixture.connect(two);
        fixture.joinCracked(handleOne);
        fixture.joinCracked(handleTwo);

        // The latch only opens when both registrations are inside repository.create at once, which
        // cannot happen if the two UUIDs were serialised against each other.
        CountDownLatch bothAtCreate = fixture.repository.pauseAt("create", 2);
        Thread first = run("register-1", () -> fixture.flow.register(handleOne, "verylongpw", "verylongpw"));
        Thread second = run("register-2", () -> fixture.flow.register(handleTwo, "verylongpw", "verylongpw"));
        await(bothAtCreate);

        assertEquals(2, fixture.registry.commitsInFlight(),
                "different UUIDs must never be ordered against each other");

        fixture.repository.resume("create");
        join(first, second);

        assertTrue(fixture.backing.findByUsername("rysiek").isPresent());
        assertTrue(fixture.backing.findByUsername("stefan").isPresent());
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------------------------ release on failure

    @Test
    void aFailedWriteReleasesTheSlotAndTheUuidIsUsableAgain() {
        FakeConnection player = FakeConnection.of("Tadek");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.repository.failAt("create", new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> fixture.flow.register(handle, "verylongpw", "verylongpw"));

        assertEquals(0, fixture.registry.commitsInFlight(), "the lease must be released");
        assertEquals(0, fixture.registry.commitSlotsTracked(), "and its slot must be freed");

        // The proof that nothing is stranded: the very next operation for the same UUID goes through.
        fixture.repository.failures.remove("create");
        assertEquals(Optional.of("register.success"),
                fixture.flow.register(handle, "verylongpw", "verylongpw").messageKey());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void aThrowingStateCallbackStillReleasesTheLease() {
        FakeConnection player = FakeConnection.of("Urszula");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        ConnectionRegistry.CommitLease lease = fixture.registry.beginCommit(handle).orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> lease.commit(() -> { throw new IllegalStateException("state callback blew up"); }));

        assertEquals(0, fixture.registry.commitsInFlight(),
                "a misbehaving state callback must not strand the UUID's commit slot");
        assertEquals(0, fixture.registry.commitSlotsTracked());
        ConnectionRegistry.CommitLease next = fixture.registry.beginCommit(handle).orElse(null);
        assertNotNull(next, "the UUID is committable again");
        next.close();
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void anAbandonedJoinReleasesEverythingItAllocated() {
        FakeConnection player = FakeConnection.of("Wiktor");
        ConnectionHandle handle = fixture.connect(player);
        fixture.disconnect(player);

        fixture.joinCracked(handle);

        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
        assertEquals(0, fixture.registry.size());
    }

    /** Nesting two commits for one UUID would silently break the invariant, so it is rejected. */
    @Test
    void aNestedCommitForTheSameUuidIsRejected() {
        FakeConnection player = FakeConnection.of("Zbyszek");
        ConnectionHandle handle = fixture.connect(player);

        try (ConnectionRegistry.CommitLease outer = fixture.registry.beginCommit(handle).orElseThrow()) {
            assertThrows(IllegalStateException.class, () -> fixture.registry.beginCommit(handle));
            assertEquals(1, fixture.registry.commitsInFlight(), "the rejected attempt must leave no trace");
        }
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------------------------ premium ordering

    /**
     * {@code /premium} decides on the account type it reads, so that read has to be inside the
     * section too: a migration request must not be granted twice because both callers saw CRACKED.
     */
    @Test
    void twoPremiumRequestsForOneAccountAreSerialisedAndOnlyTheFirstWrites() throws InterruptedException {
        fixture.seedAccount("Zofia", "verylongpw");
        UUID uuid = uuidOf("Zofia");
        FakeConnection player = new FakeConnection(uuid, "Zofia");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch atUpdate = fixture.repository.pauseAt("updateAccountType");
        AtomicReference<AuthFlow.Result> firstResult = new AtomicReference<>();
        Thread first = run("premium-1", () -> firstResult.set(fixture.flow.requestPremiumMigration(handle)));
        await(atUpdate);

        CountDownLatch secondQueued = queueLatch();
        AtomicReference<AuthFlow.Result> secondResult = new AtomicReference<>();
        Thread second = run("premium-2", () -> secondResult.set(fixture.flow.requestPremiumMigration(handle)));
        await(secondQueued);

        fixture.repository.resume("updateAccountType");
        join(first, second);

        assertEquals(1, fixture.writeCount("updateAccountType"), "only the first request may write");
        assertEquals(Optional.of("premium.requested"), firstResult.get().messageKey());
        assertEquals(Optional.of("premium.already-requested"), secondResult.get().messageKey(),
                "the second request must see the type the first one committed");
        assertEquals(AccountType.PENDING_MIGRATION,
                fixture.backing.findByUuid(uuid).orElseThrow().accountType());
        assertEquals(1, fixture.audit.actions().stream().filter("PREMIUM_REQUEST"::equals).count());
        assertEquals(0, fixture.registry.commitsInFlight());
    }
}
