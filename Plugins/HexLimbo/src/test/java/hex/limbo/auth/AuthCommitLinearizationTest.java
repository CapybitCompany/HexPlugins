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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit point of a persistent authentication has to be linearizable: there must be no
 * interleaving in which the database was written and the caller is nonetheless told the commit was
 * discarded, and none in which a discarded commit still left a row behind.
 *
 * <p>Each test holds the real {@link AuthFlow} inside the repository call it is about to make - the
 * exact window an earlier {@code isCurrent()} check could not cover - and then forces the disconnect
 * to land on one side of it or the other. No timing, no sleeps: a {@link CountDownLatch} pair marks
 * "the flow has arrived at the write" and "the write may proceed".
 *
 * <p>Everything is driven through the production {@code AuthFlow}, so the ordering under test is the
 * ordering the proxy runs.
 */
class AuthCommitLinearizationTest {

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

    // ------------------------------------------------------------------ /register

    /**
     * The window the old code could not close: the connection dies after the last check but before
     * {@code repository.create}. Taking the commit lease first means the write is never reached.
     */
    @Test
    void registerDisconnectBeforeTheCommitPointCreatesNoAccount() {
        FakeConnection player = FakeConnection.of("Wanda");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        // The disconnect wins the race to the commit point, so the lease is refused and the flow
        // never reaches repository.create at all.
        fixture.disconnect(player);

        AuthFlow.Result result = fixture.flow.register(handle, "verylongpw", "verylongpw");

        assertEquals(Optional.empty(), result.messageKey(), "a stale register must stay silent");
        assertEquals(AuthFlow.Routing.NONE, result.routing());
        assertTrue(fixture.backing.findByUsername("wanda").isEmpty(),
                "CONNECTION_STALE must never coexist with a created account");
        assertFalse(fixture.repository.writes.contains("create"), "the write must not even be attempted");
        assertTrue(fixture.sessions.created.isEmpty());
        assertTrue(fixture.audit.entries.isEmpty());
        assertFalse(handle.isAuthenticated());
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    /**
     * The mirror image: the lease is already held when the disconnect lands. The account row is
     * written, the outcome is a success, and only the player-facing half is suppressed.
     */
    @Test
    void registerDisconnectAfterTheCommitPointStillCommits() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Xena");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        CountDownLatch atCreate = fixture.repository.pauseAt("create");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("register", () -> result.set(fixture.flow.register(handle, "verylongpw", "verylongpw")));

        await(atCreate); // the lease is held and the write is in flight
        fixture.disconnect(player);
        fixture.repository.resume("create");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(fixture.backing.findByUsername("xena").isPresent(), "the account was created");
        assertTrue(handle.isAuthenticated(), "the committed registration flipped the auth state");
        assertEquals(1, fixture.sessions.created.size(), "the session belongs to the commit");
        assertTrue(fixture.audit.has("REGISTER"), "the success audit belongs to the commit");
        // ...but nothing is addressed at a player who is no longer there.
        assertEquals(Optional.empty(), result.get().messageKey(), "no confirmation for a dead socket");
        assertEquals(AuthFlow.Routing.NONE, result.get().routing(), "no routing for a dead socket");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty(), "no greeting queued");
        assertEquals(0, fixture.registry.commitsInFlight(), "the lease must be released");
    }

    // ------------------------------------------------------------------ /login

    @Test
    void loginDisconnectBeforeTheCommitPointWritesNothing() {
        fixture.seedAccount("Yuri", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Yuri".getBytes()), "Yuri");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        fixture.disconnect(player);
        AuthFlow.Result result = fixture.flow.login(handle, "verylongpw");

        assertEquals(Optional.empty(), result.messageKey());
        assertFalse(fixture.repository.writes.contains("recordSuccessfulLogin"),
                "no success bookkeeping for a connection that ended before the commit point");
        assertTrue(fixture.sessions.created.isEmpty(), "no session");
        assertFalse(fixture.audit.has("LOGIN"), "no success audit");
        assertFalse(handle.isAuthenticated());
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    @Test
    void loginDisconnectAfterTheCommitPointStillCommits() throws InterruptedException {
        fixture.seedAccount("Zoe", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Zoe".getBytes()), "Zoe");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch atWrite = fixture.repository.pauseAt("recordSuccessfulLogin");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("login", () -> result.set(fixture.flow.login(handle, "verylongpw")));

        await(atWrite);
        fixture.disconnect(player);
        fixture.repository.resume("recordSuccessfulLogin");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(handle.isAuthenticated(), "the committed login flipped the auth state");
        assertEquals(1, fixture.sessions.created.size());
        assertTrue(fixture.audit.has("LOGIN"));
        assertEquals(Optional.empty(), result.get().messageKey(), "no confirmation for a dead socket");
        assertEquals(AuthFlow.Routing.NONE, result.get().routing());
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    /**
     * A reconnect that takes the UUID over is treated exactly like a disconnect at the commit point,
     * and the old worker must not touch the new connection in any way.
     *
     * <p>Registering the new socket is immediate - the {@code LoginEvent} thread never waits - but
     * the asynchronous join that follows it queues behind the commit already in flight for the
     * UUID, so it decides on the picture its predecessor left rather than on a stale one.
     */
    @Test
    void aRegisterHeldAtTheWriteCannotAffectAReconnectThatSupersededIt() throws InterruptedException {
        UUID uuid = UUID.nameUUIDFromBytes("u:Aron".getBytes());
        FakeConnection first = new FakeConnection(uuid, "Aron");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);

        CountDownLatch atCreate = fixture.repository.pauseAt("create");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("register", () -> result.set(fixture.flow.register(oldHandle, "verylongpw", "verylongpw")));
        await(atCreate);

        // While the old worker holds its lease, the player reconnects on a new socket.
        FakeConnection second = new FakeConnection(uuid, "Aron");
        ConnectionHandle newHandle = fixture.connect(second);
        CountDownLatch joinQueued = new CountDownLatch(1);
        fixture.registry.setCommitQueuedObserver(queued -> joinQueued.countDown());
        Thread rejoin = run("rejoin", () -> fixture.joinCracked(newHandle));
        await(joinQueued); // the new join is provably parked behind the old commit

        assertEquals(1, fixture.registry.commitsInFlight(),
                "the queued join must not hold a second lease for the same UUID");

        fixture.repository.resume("create");
        worker.join(TimeUnit.SECONDS.toMillis(10));
        rejoin.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue(oldHandle.isAuthenticated(), "the old connection's own commit went through");
        assertFalse(newHandle.isAuthenticated(),
                "an old worker must never authenticate the connection that replaced it");
        assertTrue(fixture.prompts.pendingLobbyGreeting(newHandle).isEmpty(),
                "the new connection must not inherit a greeting");
        assertEquals(Optional.empty(), result.get().messageKey());
        assertTrue(second.messages.isEmpty(), "the new socket must be told nothing");
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked(), "no slot may be left allocated");
    }

    // ------------------------------------------------------------------ DB failures

    @Test
    void aFailedRegisterWriteLeavesNoAuthenticatedStateAndNoLease() {
        FakeConnection player = FakeConnection.of("Bela");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.repository.failAt("create", new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> fixture.flow.register(handle, "verylongpw", "verylongpw"));

        assertFalse(handle.isAuthenticated(),
                "a database failure must never leave an authenticated player without their row");
        assertTrue(fixture.backing.findByUsername("bela").isEmpty());
        assertEquals(0, fixture.registry.commitsInFlight(), "the lease must be released on failure");
        assertTrue(fixture.sessions.created.isEmpty());
        assertFalse(fixture.audit.has("REGISTER"));
    }

    @Test
    void aFailedLoginWriteLeavesNoAuthenticatedStateAndNoLease() {
        fixture.seedAccount("Cyryl", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Cyryl".getBytes()), "Cyryl");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        fixture.repository.failAt("recordSuccessfulLogin", new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> fixture.flow.login(handle, "verylongpw"));

        assertFalse(handle.isAuthenticated());
        assertEquals(0, fixture.registry.commitsInFlight());
        assertTrue(fixture.sessions.created.isEmpty());
        assertFalse(fixture.audit.has("LOGIN"));
    }

    // ------------------------------------------------------------------ /changepassword

    @Test
    void changePasswordDisconnectBeforeTheCommitPointDoesNotRotateTheHash() {
        fixture.seedAccount("Dagmar", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Dagmar".getBytes()), "Dagmar");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();
        String hashBefore = fixture.backing.findByUsername("dagmar").orElseThrow().passwordHash();

        fixture.disconnect(player);
        AuthFlow.Result result = fixture.flow.changePassword(handle, "verylongpw", "newlongpassword");

        assertEquals(Optional.empty(), result.messageKey(), "a stale change must stay silent");
        assertEquals(hashBefore, fixture.backing.findByUsername("dagmar").orElseThrow().passwordHash(),
                "a player who left must not have their password rotated");
        assertFalse(fixture.repository.writes.contains("updatePasswordHash"));
        assertFalse(fixture.audit.has("CHANGE_PASSWORD"));
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    @Test
    void changePasswordHeldAtTheWriteStillCommitsButStaysSilent() throws InterruptedException {
        fixture.seedAccount("Ewa", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Ewa".getBytes()), "Ewa");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();
        String hashBefore = fixture.backing.findByUsername("ewa").orElseThrow().passwordHash();

        CountDownLatch atWrite = fixture.repository.pauseAt("updatePasswordHash");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("cpw", () -> result.set(fixture.flow.changePassword(handle, "verylongpw", "newlongpassword")));

        await(atWrite);
        fixture.disconnect(player);
        fixture.repository.resume("updatePasswordHash");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(hashBefore.equals(fixture.backing.findByUsername("ewa").orElseThrow().passwordHash()),
                "the change was committed");
        assertTrue(fixture.audit.has("CHANGE_PASSWORD"));
        assertEquals(Optional.empty(), result.get().messageKey(), "no confirmation for a dead socket");
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    // ------------------------------------------------------------------ /premium

    @Test
    void premiumMigrationDisconnectBeforeTheCommitPointDoesNotChangeTheAccountType() {
        fixture.seedAccount("Filip", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Filip".getBytes()), "Filip");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();

        fixture.disconnect(player);
        AuthFlow.Result result = fixture.flow.requestPremiumMigration(handle);

        assertEquals(Optional.empty(), result.messageKey());
        assertFalse(fixture.repository.writes.contains("updateAccountType"),
                "a departed player must not have their account type changed");
        assertFalse(fixture.audit.has("PREMIUM_REQUEST"));
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    /**
     * The committed-but-silent rule applied to {@code /premium}, exactly as it already is to
     * {@code /login}, {@code /register} and {@code /changepassword}: the lease was won, so the type
     * change and its audit entry stand - but there is nobody left to confirm it to.
     */
    @Test
    void premiumMigrationHeldAtTheWriteStillCommitsButStaysSilent() throws InterruptedException {
        fixture.seedAccount("Gustaw", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Gustaw".getBytes());
        FakeConnection player = new FakeConnection(uuid, "Gustaw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch atWrite = fixture.repository.pauseAt("updateAccountType");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("premium", () -> result.set(fixture.flow.requestPremiumMigration(handle)));

        await(atWrite); // the lease is held and the type change is in flight
        fixture.disconnect(player);
        fixture.repository.resume("updateAccountType");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(AccountType.PENDING_MIGRATION,
                fixture.backing.findByUuid(uuid).orElseThrow().accountType(),
                "the committed type change stands");
        assertEquals(1, fixture.audit.actions().stream().filter("PREMIUM_REQUEST"::equals).count(),
                "and is audited exactly once");
        assertEquals(Optional.empty(), result.get().messageKey(), "no confirmation for a dead socket");
        assertEquals(AuthFlow.Routing.NONE, result.get().routing(), "no routing for a dead socket");
        assertTrue(player.messages.isEmpty(), "nothing may be sent to the socket that left");
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    /** The same, but the connection was superseded rather than closed: the replacement is untouched. */
    @Test
    void premiumMigrationHeldAtTheWriteStaysSilentAndDoesNotTouchTheReplacement() throws InterruptedException {
        fixture.seedAccount("Halina", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Halina".getBytes());
        FakeConnection first = new FakeConnection(uuid, "Halina");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(oldHandle);
        fixture.reset();

        CountDownLatch atWrite = fixture.repository.pauseAt("updateAccountType");
        AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
        Thread worker = run("premium", () -> result.set(fixture.flow.requestPremiumMigration(oldHandle)));
        await(atWrite);

        FakeConnection second = new FakeConnection(uuid, "Halina");
        ConnectionHandle newHandle = fixture.connect(second);

        fixture.repository.resume("updateAccountType");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(AccountType.PENDING_MIGRATION,
                fixture.backing.findByUuid(uuid).orElseThrow().accountType());
        assertEquals(1, fixture.audit.actions().stream().filter("PREMIUM_REQUEST"::equals).count());
        assertEquals(Optional.empty(), result.get().messageKey(),
                "a superseded handle is as stale as a closed one");
        assertEquals(AuthFlow.Routing.NONE, result.get().routing());
        assertTrue(second.messages.isEmpty(), "the replacement socket must be told nothing");
        assertTrue(newHandle.authState().isEmpty(), "and must not inherit any state from it");
        assertTrue(fixture.prompts.pendingLobbyGreeting(newHandle).isEmpty());
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    /** The mirror image: a live connection still gets its confirmation. */
    @Test
    void premiumMigrationOnALiveConnectionConfirmsNormally() {
        fixture.seedAccount("Ignacy", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Ignacy".getBytes());
        FakeConnection player = new FakeConnection(uuid, "Ignacy");
        ConnectionHandle handle = fixture.connect(player);
        fixture.sessions.sessionValid = true;
        fixture.joinCracked(handle);
        fixture.reset();

        AuthFlow.Result result = fixture.flow.requestPremiumMigration(handle);

        assertEquals(Optional.of("premium.requested"), result.messageKey());
        assertEquals(AccountType.PENDING_MIGRATION,
                fixture.backing.findByUuid(uuid).orElseThrow().accountType());
        assertTrue(fixture.audit.has("PREMIUM_REQUEST"));
        assertEquals(0, fixture.registry.commitsInFlight());
    }

    // ------------------------------------------------------------------ invariant sweep

    /**
     * The headline invariant, checked across every ordering the harness can produce: there is no
     * run in which a persistent success write happened and the caller was told the commit was
     * discarded.
     */
    @Test
    void neverStaleOutcomeTogetherWithAPersistedCommit() throws InterruptedException {
        for (int run = 0; run < 40; run++) {
            AuthFlowFixture f = new AuthFlowFixture();
            FakeConnection player = FakeConnection.of("Sweep-" + run);
            ConnectionHandle handle = f.connect(player);
            f.joinCracked(handle);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<AuthFlow.Result> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                await(start);
                result.set(f.flow.register(handle, "verylongpw", "verylongpw"));
            }, "register-" + run);
            Thread killer = new Thread(() -> {
                await(start);
                f.disconnect(player);
            }, "disconnect-" + run);
            worker.start();
            killer.start();
            start.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(10));
            killer.join(TimeUnit.SECONDS.toMillis(10));

            boolean accountExists = f.backing.findByUsername(("Sweep-" + run).toLowerCase()).isPresent();
            boolean reportedSilent = result.get().messageKey().isEmpty();
            boolean committed = handle.isAuthenticated();

            // Exactly two legal shapes: nothing happened, or everything happened.
            if (accountExists) {
                assertTrue(committed, "run " + run + ": a created account must come with a committed state");
                assertTrue(f.audit.has("REGISTER"), "run " + run + ": committed register must be audited");
                assertEquals(1, f.sessions.created.size(), "run " + run + ": committed register creates a session");
            } else {
                assertFalse(committed, "run " + run + ": no account means no authenticated state");
                assertTrue(reportedSilent, "run " + run + ": no account means the caller was told nothing");
                assertTrue(f.audit.entries.isEmpty(), "run " + run + ": no account means no audit");
                assertEquals(List.of(), f.sessions.created, "run " + run + ": no account means no session");
            }
            assertEquals(0, f.registry.commitsInFlight(), "run " + run + ": no lease may leak");
        }
    }
}
