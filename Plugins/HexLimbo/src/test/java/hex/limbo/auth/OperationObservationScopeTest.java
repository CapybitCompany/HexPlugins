package hex.limbo.auth;

import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A thread must not keep hold of the last connection it happened to serve.
 *
 * <p>An operation stamp carries a {@link ConnectionHandle}, and through it the concrete
 * {@code Player} and its {@code Audience}. Parking one in a thread-local on <em>every</em> ordered
 * section - as an earlier version did - means every pooled auth-executor thread and every Velocity
 * event thread pins the last player it touched until it happens to serve another; on externally
 * managed threads it can pin the plugin classloader with it. Only the failure-observation scope that
 * {@link FlowResultApplier#execute} opens is allowed to record anything, it records two
 * {@code long}s rather than references, and it is removed on every path.
 *
 * <p>{@link ConnectionRegistry#hasObservationScope()} is the diagnostic seam used here, so nothing
 * has to reach for weak references or a garbage collector to get a deterministic answer.
 */
class OperationObservationScopeTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    private static UUID uuidOf(String username) {
        return UUID.nameUUIDFromBytes(("u:" + username).getBytes());
    }

    /** Runs {@code body} on its own thread and reports whether a scope survived it. */
    private boolean scopeSurvives(Runnable body) throws InterruptedException {
        AtomicBoolean leaked = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            body.run();
            leaked.set(fixture.registry.hasObservationScope());
        }, "worker");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(worker.isAlive(), "the worker never finished");
        return leaked.get();
    }

    // ------------------------------------------------- paths that must record nothing at all

    @Test
    void theJoinPipelineLeavesNoObservationScopeOnItsWorker() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Alina");
        ConnectionHandle handle = fixture.connect(player);

        assertFalse(scopeSurvives(() -> fixture.joinCracked(handle)),
                "resolveJoin has no failure to observe and must record nothing");
    }

    @Test
    void forceLogoutLeavesNoObservationScope() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Blazej"), "Blazej");
        fixture.seedAccount("Blazej", "verylongpw");
        fixture.sessions.sessionValid = true;
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        assertFalse(scopeSurvives(() -> fixture.flow.forceLogout(handle)));
    }

    @Test
    void directAuthServiceOperationsLeaveNoObservationScope() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Cyryl");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        assertFalse(scopeSurvives(() ->
                fixture.authService.attemptRegister(handle, "verylongpw", "verylongpw", false)));
        assertFalse(scopeSurvives(() -> fixture.authService.logout(handle)));
        assertFalse(scopeSurvives(() ->
                fixture.authService.changePassword(handle, "verylongpw", "evenlongerpw")));
    }

    @Test
    void applyingAResultLeavesNoObservationScope() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Dorian"), "Dorian");
        fixture.seedAccount("Dorian", "verylongpw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        assertFalse(scopeSurvives(() -> fixture.apply(fixture.flow.login(handle, "verylongpw"), player)));
    }

    // ------------------------------------------------------- the scope that does exist is closed

    @Test
    void executeClosesItsScopeAfterSuccess() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Ewelina"), "Ewelina");
        fixture.seedAccount("Ewelina", "verylongpw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        assertFalse(scopeSurvives(() -> fixture.execute(
                "/login", handle, player, () -> fixture.flow.login(handle, "verylongpw"))));
    }

    @Test
    void executeClosesItsScopeAfterAFailureInsideTheCommitSection() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Fabian");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.repository.failAt("create", new IllegalStateException("db down"));

        assertFalse(scopeSurvives(() -> fixture.execute(
                "/register", handle, player,
                () -> fixture.flow.register(handle, "verylongpw", "verylongpw"))));
    }

    @Test
    void executeClosesItsScopeAfterAFailureBeforeTheCommitSection() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Gerard");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.premiumResolver = name -> {
            throw new IllegalStateException("Mojang lookup blew up");
        };

        assertFalse(scopeSurvives(() -> fixture.execute(
                "/register", handle, player,
                () -> fixture.flow.register(handle, "verylongpw", "verylongpw"))));
    }

    @Test
    void executeClosesItsScopeWhenThePlayerEffectItselfThrows() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Halszka"), "Halszka");
        fixture.seedAccount("Halszka", "verylongpw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        FlowResultApplier.Effects exploding = new FlowResultApplier.Effects() {
            @Override public void sendMessage(String key, Object[] args) {
                throw new IllegalStateException("the proxy blew up mid-send");
            }
            @Override public void disconnect(String key, Object[] args) { }
            @Override public java.util.concurrent.CompletionStage<RouteCoordinator.RouteResult>
                    sendToTarget() { return null; }
            @Override public java.util.concurrent.CompletionStage<RouteCoordinator.RouteResult>
                    sendToLimbo() { return null; }
        };

        AtomicBoolean leaked = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            assertThrows(IllegalStateException.class, () -> FlowResultApplier.execute(
                    fixture.registry, handle, player, exploding, "/login",
                    org.slf4j.LoggerFactory.getLogger(getClass()),
                    () -> fixture.flow.login(handle, "verylongpw")));
            leaked.set(fixture.registry.hasObservationScope());
        }, "worker");
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(leaked.get(), "a throwing player effect must not strand the scope either");
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------------------- a reused executor thread

    /**
     * The retention that mattered: one pooled thread serves player A, then player B. It must neither
     * still be holding A's connection nor attribute B's failure to A's operation.
     */
    @Test
    void aReusedThreadNeitherKeepsNorMisattributesThePreviousPlayer() throws InterruptedException {
        FakeConnection first = FakeConnection.of("Ignacy");
        ConnectionHandle firstHandle = fixture.connect(first);
        fixture.joinCracked(firstHandle);

        FakeConnection second = FakeConnection.of("Jadwiga");
        ConnectionHandle secondHandle = fixture.connect(second);
        fixture.joinCracked(secondHandle);
        fixture.premiumResolver = name -> {
            throw new IllegalStateException("Mojang lookup blew up");
        };

        CountDownLatch done = new CountDownLatch(1);
        Thread pooled = new Thread(() -> {
            // A's operation runs first and must leave nothing behind.
            fixture.flow.forceLogout(firstHandle);
            assertFalse(fixture.registry.hasObservationScope(),
                    "the first player's operation must not open or leave a scope");
            // B's attempt then fails before its own commit section.
            fixture.execute("/register", secondHandle, second,
                    () -> fixture.flow.register(secondHandle, "verylongpw", "verylongpw"));
            done.countDown();
        }, "pooled");
        pooled.start();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pooled.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(fixture.registry.hasObservationScope());
        assertTrue(fixture.effectsFor(second).messageKeys.contains("error.internal"),
                "B's own failure is reported for B");
        assertTrue(fixture.effectsFor(first).isSilent(),
                "and nothing at all is attributed to the player the thread served before");
    }
}
