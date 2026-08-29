package hex.limbo.auth;

import hex.limbo.listener.ServerConnectListener;
import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static hex.limbo.auth.RouteCoordinator.Destination.LIMBO;
import static hex.limbo.auth.RouteCoordinator.Destination.TARGET;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.CONNECTION_CANCELLED;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.CONNECTION_IN_PROGRESS;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.SERVER_DISCONNECTED;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering the <em>decisions</em> is not the same as ordering the <em>transfers</em>.
 *
 * <p>A Velocity connection request is asynchronous, and while one is running a second is refused
 * outright with {@code CONNECTION_IN_PROGRESS}. Applying results in order therefore still left two
 * broken end states reachable: an unauthenticated player stranded on the target because a
 * {@code /login} transfer completed after the {@code /logout} that should have pulled them back, and
 * an authenticated player stranded in the limbo - with no prompt, because they <em>are</em>
 * authenticated - because a {@code /logout} transfer completed after the {@code /login} that
 * followed it.
 *
 * <p>Nothing here counts routing calls. Every transfer is a real in-flight request that finishes
 * only when the test says so and with the status the test picks, which is exactly the window the bug
 * lived in. The invariant under test:
 *
 * <blockquote>once every started connection request has completed, the live connection is on the
 * backend belonging to the newest valid auth/routing operation, and no older transfer can overwrite
 * that.</blockquote>
 */
class RouteOrderingTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    /** The real listener; only the pieces {@code handleArrival} needs are wired. */
    private final ServerConnectListener listener = new ServerConnectListener(
            fixture.authService, null, fixture.routes, fixture.context, fixture.prompts);

    /**
     * Every test in this class has to end converged: no connection still waiting to be moved, no
     * timer still armed, no transfer still open, no slot or observation scope held. A test that
     * deliberately creates a follow-up decision has to finish it or end the connection, exactly as
     * the proxy would - otherwise the cleanup this class claims to prove would only be claimed.
     */
    @AfterEach
    void everyDecisionConverged() {
        for (ConnectionHandle handle : fixture.opened) {
            assertFalse(fixture.routes.hasPendingWork(handle),
                    "routing work left over for " + handle);
        }
        assertTrue(fixture.routeScheduler.pending().isEmpty(),
                "retry timers or watchdogs still armed: " + fixture.routeScheduler.pending().size());
        assertEquals(0, fixture.transport.inFlight(), "a transfer was left in flight");
        assertEquals(0, fixture.registry.commitsInFlight(), "a commit lease leaked");
        assertEquals(0, fixture.registry.commitSlotsTracked(), "a commit slot leaked");
        assertFalse(fixture.registry.hasObservationScope(), "a failure-observation scope leaked");
    }

    private static UUID uuidOf(String username) {
        return UUID.nameUUIDFromBytes(("u:" + username).getBytes());
    }

    /** A connection that is registered, joined and still has to log in. */
    private ConnectionHandle joined(String username, FakeConnection player) {
        fixture.seedAccount(username, "verylongpw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        return handle;
    }

    /** Runs {@code /login} and applies its result the way the command does. */
    private void login(ConnectionHandle handle, FakeConnection player) {
        assertTrue(fixture.apply(fixture.flow.login(handle, "verylongpw"), player).applied(),
                "precondition: the login result must have been applied");
    }

    /** Runs {@code /logout} and applies its result the way the command does. */
    private void logout(ConnectionHandle handle, FakeConnection player) {
        assertTrue(fixture.apply(fixture.flow.logout(handle), player).applied(),
                "precondition: the logout result must have been applied");
    }

    /** Delivers a {@code ServerConnectedEvent} through the production arrival handler. */
    private void arrive(ConnectionHandle handle, FakeConnection player, RouteCoordinator.Destination where) {
        listener.handleArrival(handle, player, where == LIMBO, where == TARGET, false);
    }

    // ------------------------------------------------------- a newer wish is never lost

    /**
     * Case A. The login's transfer to the target is still running when the logout is applied. The
     * limbo must not be dropped on the floor, and the target transfer must not be the last word.
     */
    @Test
    void aLimboRouteIsNotLostWhileATargetTransferIsStillRunning() {
        FakeConnection player = new FakeConnection(uuidOf("Adam"), "Adam");
        ConnectionHandle handle = joined("Adam", player);

        login(handle, player);
        assertEquals(List.of(TARGET), fixture.transport.destinations());
        assertTrue(fixture.routes.isTransferInFlight(handle), "the target transfer is running");

        // The logout lands while that request is still in flight. It is not issued now - Velocity
        // would refuse it - but it becomes the destination that has to be reached.
        logout(handle, player);
        assertEquals(List.of(TARGET), fixture.transport.destinations(),
                "no second request may be fired at Velocity while one is running");
        assertEquals(LIMBO, fixture.routes.desiredRoute(handle), "the newer wish is remembered");

        // The older transfer finally completes. The newest wish is executed straight afterwards.
        fixture.transport.finish(0, SUCCESS);
        assertEquals(List.of(TARGET, LIMBO), fixture.transport.destinations(),
                "the limbo transfer is issued as soon as the target one is out of the way");

        fixture.transport.finish(1, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle), "and the connection has reached where it belongs");
        assertFalse(fixture.routes.isTransferInFlight(handle));
        assertFalse(handle.isAuthenticated(), "the logout is the last word on the auth state too");
    }

    /** Case B, the mirror image: an authenticated player must not be left sitting in the limbo. */
    @Test
    void aTargetRouteIsNotLostWhileALimboTransferIsStillRunning() {
        FakeConnection player = new FakeConnection(uuidOf("Beata"), "Beata");
        ConnectionHandle handle = joined("Beata", player);
        login(handle, player);
        fixture.transport.finish(0, SUCCESS);

        logout(handle, player);
        assertEquals(LIMBO, fixture.transport.latest().destination());
        assertTrue(fixture.routes.isTransferInFlight(handle));

        // The player logs straight back in while the limbo transfer is still running.
        login(handle, player);
        assertEquals(List.of(TARGET, LIMBO), fixture.transport.destinations(),
                "the target request is held back rather than refused");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle));

        fixture.transport.finish(1, SUCCESS);
        assertEquals(List.of(TARGET, LIMBO, TARGET), fixture.transport.destinations(),
                "the older limbo transfer does not get to be the end state");
        fixture.transport.finish(2, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle));
        assertTrue(handle.isAuthenticated());
    }

    /** Even a chain of wishes collapses onto the newest one rather than replaying every hop. */
    @Test
    void onlyTheNewestWishIsExecutedAfterAnOlderTransferCompletes() {
        FakeConnection player = new FakeConnection(uuidOf("Cyprian"), "Cyprian");
        ConnectionHandle handle = joined("Cyprian", player);

        login(handle, player);                    // TARGET, in flight
        logout(handle, player);                   // wants LIMBO
        login(handle, player);                    // ...no, wants TARGET again
        assertEquals(List.of(TARGET), fixture.transport.destinations(),
                "the intermediate wishes are coalesced, not queued up as transfers");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle));

        fixture.transport.finish(0, SUCCESS);
        // The completed transfer already served the newest wish, so nothing further is issued.
        assertEquals(List.of(TARGET), fixture.transport.destinations());
        assertNull(fixture.routes.desiredRoute(handle));
    }

    // ------------------------------------------------------------------ transfer statuses

    /**
     * {@code CONNECTION_IN_PROGRESS} means somebody else's transfer is running. The wish is kept -
     * retrying on the spot would spin against that transfer - and executed when the player next
     * lands somewhere.
     */
    @Test
    void connectionInProgressKeepsTheWishAndRetriesOnTheNextArrival() {
        FakeConnection player = new FakeConnection(uuidOf("Dorota"), "Dorota");
        ConnectionHandle handle = joined("Dorota", player);

        login(handle, player);
        fixture.transport.finish(0, CONNECTION_IN_PROGRESS);

        assertFalse(fixture.routes.isTransferInFlight(handle), "nothing may stay stuck in flight");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle), "the wish survives the refusal");
        assertEquals(List.of(TARGET), fixture.transport.destinations(), "and is not retried in a spin");

        // The foreign transfer lands the player in the limbo; that is the trigger to try again.
        arrive(handle, player, LIMBO);
        assertEquals(List.of(TARGET, TARGET), fixture.transport.destinations(),
                "the newest wish is requested again once the player is somewhere");
        fixture.transport.finish(1, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle));
    }

    @Test
    void aCancelledTransferLeavesNoBlockedRoutingState() {
        FakeConnection player = new FakeConnection(uuidOf("Edyta"), "Edyta");
        ConnectionHandle handle = joined("Edyta", player);

        login(handle, player);
        fixture.transport.finish(0, CONNECTION_CANCELLED);

        assertFalse(fixture.routes.isTransferInFlight(handle));
        assertEquals(List.of(TARGET), fixture.transport.destinations(), "no endless retry of a refusal");

        // The very next decision routes immediately: nothing is blocked.
        logout(handle, player);
        assertEquals(List.of(TARGET, LIMBO), fixture.transport.destinations());
        assertTrue(fixture.routes.isTransferInFlight(handle));
        fixture.transport.finish(1, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle));
    }

    @Test
    void aBackendErrorLeavesNoBlockedRoutingState() {
        FakeConnection player = new FakeConnection(uuidOf("Filip"), "Filip");
        ConnectionHandle handle = joined("Filip", player);

        login(handle, player);
        fixture.transport.finish(0, SERVER_DISCONNECTED);

        assertFalse(fixture.routes.isTransferInFlight(handle));
        logout(handle, player);
        assertEquals(List.of(TARGET, LIMBO), fixture.transport.destinations(),
                "a failed transfer must not wedge the connection's routing");
        fixture.transport.finish(1, SUCCESS);
        assertFalse(fixture.routes.isTransferInFlight(handle));
    }

    // ------------------------------------------------ disconnect and reconnect mid-transfer

    @Test
    void aDisconnectDuringATransferStopsAnyFurtherMovement() {
        FakeConnection player = new FakeConnection(uuidOf("Gabriela"), "Gabriela");
        ConnectionHandle handle = joined("Gabriela", player);

        login(handle, player);
        logout(handle, player); // LIMBO is now wanted, held behind the target transfer
        fixture.disconnect(player);

        fixture.transport.finish(0, SUCCESS);

        assertEquals(List.of(TARGET), fixture.transport.destinations(),
                "a connection that has ended must not be moved anywhere else");
        assertNull(fixture.routes.desiredRoute(handle));
        assertFalse(fixture.routes.isTransferInFlight(handle));
    }

    @Test
    void aReconnectDuringATransferLeavesTheNewConnectionAlone() {
        UUID uuid = uuidOf("Henryk");
        FakeConnection first = new FakeConnection(uuid, "Henryk");
        ConnectionHandle oldHandle = joined("Henryk", first);

        login(oldHandle, first);
        assertTrue(fixture.routes.isTransferInFlight(oldHandle));

        // The player reconnects while the old socket's transfer is still running.
        FakeConnection second = new FakeConnection(uuid, "Henryk");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        fixture.transport.finish(0, SUCCESS);

        assertEquals(List.of(TARGET), fixture.transport.destinations(),
                "the old callback must not issue a transfer for anybody");
        assertNull(fixture.routes.desiredRoute(newHandle),
                "and must not leave a routing wish on the connection that replaced it");
        assertFalse(fixture.routes.isTransferInFlight(newHandle));
        assertTrue(fixture.transport.started.stream().noneMatch(s -> s.connection() == second),
                "no transfer was ever issued for the new socket");
        assertFalse(newHandle.isAuthenticated(), "which still has to log in for itself");
    }

    // ------------------------------------------------------------------ arrival repair

    /**
     * The end state an older transfer could produce: an unauthenticated player on a real backend.
     * The arrival is refused and the player is sent back where they belong.
     */
    @Test
    void anUnauthenticatedArrivalOnTheTargetIsCorrectedToTheLimbo() {
        FakeConnection player = new FakeConnection(uuidOf("Iwona"), "Iwona");
        ConnectionHandle handle = joined("Iwona", player);
        assertFalse(handle.isAuthenticated(), "precondition: this player never logged in");

        arrive(handle, player, TARGET);

        assertEquals(List.of(LIMBO), fixture.transport.destinations(),
                "an unauthenticated player on the target is put back into the limbo");
        assertEquals(LIMBO, fixture.routes.desiredRoute(handle));
        fixture.transport.finish(0, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle));
        assertTrue(player.titles.isEmpty(), "and is certainly not congratulated on arriving");
    }

    /** The other end state: an authenticated player in the limbo whose target is still wanted. */
    @Test
    void anAuthenticatedArrivalInTheLimboReRequestsAStillWantedTarget() {
        FakeConnection player = new FakeConnection(uuidOf("Jakub"), "Jakub");
        ConnectionHandle handle = joined("Jakub", player);

        login(handle, player);
        fixture.transport.finish(0, CONNECTION_IN_PROGRESS); // an older transfer wins the race

        arrive(handle, player, LIMBO);

        assertEquals(List.of(TARGET, TARGET), fixture.transport.destinations(),
                "the target the login asked for is requested again");
        assertTrue(handle.isAuthenticated());
        assertFalse(fixture.prompts.hasActivePrompt(handle),
                "an authenticated player must not be given a login prompt in the limbo");

        fixture.transport.finishLatest(SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle), "and the re-issued transfer settles it");
    }

    /** ...but a deliberate stay in the limbo is left alone, rather than overridden on auth state. */
    @Test
    void anAuthenticatedPlayerWithNoPendingRouteIsLeftWhereTheyAre() {
        FakeConnection player = new FakeConnection(uuidOf("Kinga"), "Kinga");
        ConnectionHandle handle = joined("Kinga", player);

        login(handle, player);
        fixture.transport.finish(0, SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle), "precondition: nothing is pending");

        // /limbo put them here on purpose.
        arrive(handle, player, LIMBO);

        assertEquals(List.of(TARGET), fixture.transport.destinations(),
                "no transfer may be forced on the strength of isAuthenticated() alone");
        assertNull(fixture.routes.desiredRoute(handle));
    }

    // ------------------------------------------------------------------ greeting timing

    /**
     * The greeting is tied to a confirmed arrival on the <em>right</em> server. A detour through the
     * limbo, caused by a transfer that lost the race, must neither release it nor lose it.
     */
    @Test
    void theGreetingSurvivesAWrongServerDetourAndIsReleasedOnlyOnTheTarget() {
        FakeConnection player = new FakeConnection(uuidOf("Lucjan"), "Lucjan");
        ConnectionHandle handle = joined("Lucjan", player);

        login(handle, player);
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isPresent(), "the greeting is queued");
        fixture.transport.finish(0, CONNECTION_IN_PROGRESS);

        // An older transfer lands them in the limbo instead.
        arrive(handle, player, LIMBO);
        assertTrue(player.titles.isEmpty(), "nothing is shown for landing on the wrong server");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isPresent(),
                "and the greeting is kept for the arrival that does count");

        // The re-issued target transfer succeeds and the player really gets there.
        fixture.transport.finish(1, SUCCESS);
        arrive(handle, player, TARGET);

        assertEquals(1, player.titles.size(), "exactly one greeting, on the confirmed target arrival");
        assertEquals("Witamy na Hex!",
                FakeConnection.plain(player.titles.get(0).subtitle()));
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
    }

    // ------------------------------------------------- bounded recovery and the fail-closed exit

    /** Armed retry timers, ignoring the watchdog every in-flight transfer also arms. */
    private List<AuthFlowFixture.ManualScheduler.Task> pendingRetries() {
        return fixture.routeScheduler.pending().stream()
                .filter(task -> task.delayMillis != RouteCoordinator.TRANSFER_TIMEOUT_MILLIS)
                .toList();
    }

    /** Drives every attempt of the current decision to {@code status}, running the retry timers. */
    private void failEveryAttempt(RouteCoordinator.TransferStatus status) {
        for (int attempt = 0; attempt < RouteCoordinator.MAX_ATTEMPTS; attempt++) {
            fixture.transport.finishLatest(status);
            fixture.routeScheduler.runPending();
        }
    }

    /**
     * The case that used to have no terminal state at all: the player is demoted on the target, the
     * limbo transfer fails, and nothing else ever happens - no further command, no arrival event.
     * Leaving them there would put an unauthenticated player past the authentication boundary, so
     * after a bounded number of attempts the connection is closed instead.
     */
    @Test
    void aLimboTransferThatKeepsFailingEndsInAFailClosedDisconnect() {
        for (RouteCoordinator.TransferStatus status : List.of(
                RouteCoordinator.TransferStatus.SERVER_DISCONNECTED,
                RouteCoordinator.TransferStatus.CONNECTION_CANCELLED,
                RouteCoordinator.TransferStatus.UNAVAILABLE,
                RouteCoordinator.TransferStatus.CONNECTION_IN_PROGRESS)) {

            AuthFlowFixture f = new AuthFlowFixture();
            f.seedAccount("Ludwik", "verylongpw");
            f.sessions.sessionValid = true;
            FakeConnection player = new FakeConnection(uuidOf("Ludwik"), "Ludwik");
            ConnectionHandle handle = f.connect(player);
            f.joinCracked(handle);
            f.reset();
            // Authenticated and confirmed on the target, which is where the danger is.
            new ServerConnectListener(f.authService, null, f.routes, f.context, f.prompts)
                    .handleArrival(handle, player, false, true, false);

            f.apply(f.flow.logout(handle), player);
            assertEquals(RouteCoordinator.Destination.LIMBO, f.transport.latest().destination());

            for (int attempt = 0; attempt < RouteCoordinator.MAX_ATTEMPTS; attempt++) {
                f.transport.finishLatest(status);
                f.routeScheduler.runPending();
            }

            String because = "status " + status;
            assertEquals(RouteCoordinator.MAX_ATTEMPTS, f.transport.started.size(),
                    because + ": the recovery must be bounded, not endless");
            assertEquals(List.of(player), f.transport.disconnected,
                    because + ": an unauthenticated player who cannot reach the limbo must be closed");
            assertEquals(List.of("disconnect.limbo-unavailable"), f.transport.disconnectReasons, because);
            assertFalse(f.routes.hasPendingWork(handle), because + ": and nothing may be left pending");
        }
    }

    /**
     * A decision for a destination the connection is confirmed to be on is answered on the spot. No
     * redundant transfer means no redundant way for it to fail, and nothing that could then be
     * mistaken for "we could not put them there".
     */
    @Test
    void aRouteToAConfirmedDestinationIssuesNoTransferAtAll() {
        FakeConnection player = new FakeConnection(uuidOf("Maria"), "Maria");
        ConnectionHandle handle = joined("Maria", player);
        arrive(handle, player, LIMBO); // confirmed: they are where they belong

        RouteCoordinator.RouteResult result = fixture.routes
                .route(handle, player, LIMBO)
                .toCompletableFuture().getNow(null);

        assertEquals(RouteCoordinator.RouteResult.ALREADY_THERE, result);
        assertTrue(fixture.transport.started.isEmpty(), "no transfer may be issued");
        assertTrue(fixture.transport.disconnected.isEmpty());
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /** An authenticated player whose target transfer fails stays put; nothing unsafe about it. */
    @Test
    void aFailedTargetTransferNeverClosesAnAuthenticatedConnection() {
        FakeConnection player = new FakeConnection(uuidOf("Norbert"), "Norbert");
        ConnectionHandle handle = joined("Norbert", player);

        login(handle, player);
        failEveryAttempt(SERVER_DISCONNECTED);

        assertTrue(handle.isAuthenticated());
        assertTrue(fixture.transport.disconnected.isEmpty(),
                "an authenticated player is safe wherever they are");
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /**
     * {@code CONNECTION_IN_PROGRESS} used to wait for an arrival event that might never come. It is
     * now retried on a timer like any other non-arrival, and runs out the same way.
     */
    @Test
    void connectionInProgressIsRecoveredWithoutWaitingForAnArrivalThatNeverComes() {
        FakeConnection player = new FakeConnection(uuidOf("Olga"), "Olga");
        ConnectionHandle handle = joined("Olga", player);

        login(handle, player);
        fixture.transport.finishLatest(CONNECTION_IN_PROGRESS);

        assertEquals(1, pendingRetries().size(), "a retry is armed, not a hope");
        fixture.routeScheduler.runPending();
        assertEquals(2, fixture.transport.started.size(), "the retry really re-issued the transfer");

        fixture.transport.finishLatest(SUCCESS);
        assertNull(fixture.routes.desiredRoute(handle));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /**
     * A transport stage Velocity never completes must not leave the connection half-routed. The
     * watchdog closes the attempt out, and a late answer to that abandoned request changes nothing.
     */
    @Test
    void aTransferThatNeverReportsBackIsClosedOutByTheWatchdog() {
        FakeConnection player = new FakeConnection(uuidOf("Patrycja"), "Patrycja");
        ConnectionHandle handle = joined("Patrycja", player);

        login(handle, player);
        AuthFlowFixture.ControllableTransport.Started stuck = fixture.transport.latest();
        assertTrue(fixture.routes.isTransferInFlight(handle));

        // Nobody completes the future; only the watchdog fires.
        fixture.routeScheduler.runLongestPending();
        assertFalse(fixture.routes.isTransferInFlight(handle), "the attempt was closed out");

        fixture.routeScheduler.runPending(); // the retry the watchdog armed
        assertEquals(2, fixture.transport.started.size());

        // The original request finally answers, long after it was abandoned.
        stuck.future().complete(SUCCESS);
        assertEquals(2, fixture.transport.started.size(),
                "a late answer to an abandoned attempt must not start or settle anything");
        assertTrue(fixture.routes.isTransferInFlight(handle), "the live attempt is untouched");

        fixture.transport.finishLatest(SUCCESS);
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    @Test
    void aDisconnectDuringARetryStopsTheRecoveryCompletely() {
        FakeConnection player = new FakeConnection(uuidOf("Rafal"), "Rafal");
        ConnectionHandle handle = joined("Rafal", player);

        login(handle, player);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        assertEquals(1, pendingRetries().size());

        fixture.disconnect(player);
        assertTrue(fixture.routeScheduler.pending().isEmpty(), "the armed retry is cancelled outright");

        fixture.routeScheduler.runPending();
        assertEquals(1, fixture.transport.started.size(), "and nothing further is issued");
        assertFalse(fixture.routes.hasPendingWork(handle), "no state survives the disconnect");
        assertTrue(fixture.transport.disconnected.isEmpty(), "a socket that is gone is not kicked again");
    }

    @Test
    void aReconnectDuringARetryLeavesTheNewConnectionAlone() {
        UUID uuid = uuidOf("Sabina");
        FakeConnection first = new FakeConnection(uuid, "Sabina");
        ConnectionHandle oldHandle = joined("Sabina", first);

        login(oldHandle, first);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        assertEquals(1, pendingRetries().size());

        FakeConnection second = new FakeConnection(uuid, "Sabina");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        fixture.routeScheduler.runPending();

        assertEquals(1, fixture.transport.started.size(),
                "the old connection's retry must not move anybody");
        assertFalse(fixture.routes.hasPendingWork(newHandle),
                "and must not leave routing state on the connection that replaced it");
        assertTrue(fixture.transport.disconnected.isEmpty(),
                "least of all close the new socket on the old one's behalf");
    }

    @Test
    void aNewerDecisionDuringRecoveryCancelsTheRetryAndGoesStraightAway() {
        FakeConnection player = new FakeConnection(uuidOf("Tadeusz"), "Tadeusz");
        ConnectionHandle handle = joined("Tadeusz", player);

        login(handle, player);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        assertEquals(1, fixture.routeScheduler.pending().size());

        logout(handle, player); // a newer decision, while the retry timer is armed
        assertTrue(pendingRetries().isEmpty(), "the stale retry is cancelled");
        assertEquals(List.of(TARGET, LIMBO), fixture.transport.destinations(),
                "and the newer destination is issued immediately");

        fixture.transport.finishLatest(SUCCESS);
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    // --------------------------------------- the terminal path is bound to its own decision

    /**
     * The window the terminal path used to leave open: the last attempt of decision A fails, and a
     * newer decision B is registered before A finishes tearing itself down. A must end <em>its
     * own</em> decision and nothing else - not B's desired destination, not B's future.
     *
     * <p>B is registered from inside the fail-closed disconnect, which is the deepest point of A's
     * terminal handling.
     */
    @Test
    void aTerminalFailureNeverClearsOrFailsANewerDecision() {
        FakeConnection player = new FakeConnection(uuidOf("Urszula"), "Urszula");
        ConnectionHandle handle = joined("Urszula", player);

        CompletionStage<RouteCoordinator.RouteResult> decisionA =
                fixture.routes.route(handle, player, LIMBO);

        // Run the first two attempts out, then arm the hook that fires in the window between the
        // routing lock being released and the terminal path running.
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();

        AtomicReference<CompletionStage<RouteCoordinator.RouteResult>> decisionB = new AtomicReference<>();
        fixture.routeScheduler.onNextCancel =
                () -> decisionB.compareAndSet(null, fixture.routes.route(handle, player, TARGET));
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertNotNull(decisionA.toCompletableFuture().getNow(null), "A ends its own decision");
        assertTrue(decisionA.toCompletableFuture().getNow(null).failed(),
                "and ends it as the failure it was");
        assertNotNull(decisionB.get(), "precondition: B really was registered inside A's teardown");
        assertNull(decisionB.get().toCompletableFuture().getNow(null),
                "A must not settle B's future");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle),
                "and must not wipe the destination B asked for");

        // B is a real decision and has to be finished like one, so the cleanup check below means
        // something.
        fixture.transport.finishLatest(SUCCESS);
        assertEquals(RouteCoordinator.RouteResult.REACHED,
                decisionB.get().toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
        assertTrue(pendingRetries().isEmpty(), "no retry timer may outlive the test");
    }

    /**
     * The other half: A works out that it should close the connection, and a login authenticates it
     * in the meantime. A must not disconnect a connection that is legitimately authenticated again -
     * the teardown runs inside the connection's own commit order and re-reads
     * {@code handle.isAuthenticated()} there. It is the authentication that stops it, not the fact
     * that some newer operation exists; the tests below cover the operations that mint a fresh id
     * without authenticating anybody.
     */
    @Test
    void aStaleFailClosedPathNeverDisconnectsAReAuthenticatedConnection() {
        FakeConnection player = new FakeConnection(uuidOf("Wiktoria"), "Wiktoria");
        ConnectionHandle handle = joined("Wiktoria", player);
        login(handle, player);
        fixture.transport.finishLatest(SUCCESS);
        arrive(handle, player, TARGET);
        fixture.reset();

        // A force-logout demotes them and asks for the limbo; every attempt fails.
        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        // ...and before the last attempt runs out, the player logs back in.
        assertEquals(AuthService.LoginOutcome.SUCCESS,
                fixture.authService.attemptLogin(handle, "verylongpw"));
        assertTrue(handle.isAuthenticated(), "precondition: a newer operation authenticated them");

        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertTrue(fixture.transport.disconnected.isEmpty(),
                "a stale fail-closed path must never close a re-authenticated connection");
        assertEquals(RouteCoordinator.RouteResult.FAILED_CONNECTION_KEPT,
                applied.routing().orElseThrow().toCompletableFuture().getNow(null),
                "and it says so, rather than claiming the connection was closed");
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    @Test
    void aDisconnectInsideTheTerminalPathStillSettlesTheDecisionCleanly() {
        FakeConnection player = new FakeConnection(uuidOf("Xawery"), "Xawery");
        ConnectionHandle handle = joined("Xawery", player);

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(handle, player, LIMBO);
        fixture.transport.beforeDisconnect = () -> fixture.disconnect(player);

        failEveryAttempt(SERVER_DISCONNECTED);

        assertNotNull(decision.toCompletableFuture().getNow(null),
                "the decision is settled exactly once, whatever happens in the teardown");
        assertFalse(fixture.routes.hasPendingWork(handle), "and nothing is left behind");
    }

    @Test
    void aReconnectInsideTheTerminalPathLeavesTheNewConnectionAlone() {
        UUID uuid = uuidOf("Zenon");
        FakeConnection first = new FakeConnection(uuid, "Zenon");
        ConnectionHandle oldHandle = joined("Zenon", first);
        FakeConnection second = new FakeConnection(uuid, "Zenon");
        AtomicReference<ConnectionHandle> replacement = new AtomicReference<>();

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(oldHandle, first, LIMBO);
        fixture.transport.beforeDisconnect = () -> replacement.compareAndSet(null, fixture.connect(second));

        failEveryAttempt(SERVER_DISCONNECTED);

        assertNotNull(decision.toCompletableFuture().getNow(null));
        assertNotNull(replacement.get(), "precondition: the reconnect happened inside the teardown");
        assertFalse(fixture.routes.hasPendingWork(replacement.get()),
                "the new connection must inherit no routing state");
        assertTrue(fixture.transport.started.stream().noneMatch(t -> t.connection() == second),
                "and no transfer may be issued for it");
    }

    /** A throwing teardown must not leave the decision hanging or the state dirty. */
    @Test
    void aThrowingFailClosedDisconnectStillSettlesTheDecision() {
        FakeConnection player = new FakeConnection(uuidOf("Zuzanna"), "Zuzanna");
        ConnectionHandle handle = joined("Zuzanna", player);
        fixture.transport.disconnectFailure = new IllegalStateException("the socket blew up");

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(handle, player, LIMBO);
        failEveryAttempt(SERVER_DISCONNECTED);

        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECT_UNKNOWN,
                decision.toCompletableFuture().getNow(null),
                "after a throwing disconnect neither 'closed' nor 'still open' is known");
        assertFalse(fixture.routes.hasPendingWork(handle), "no pending future, no armed timer");
        // ...and the connection is immediately routable again.
        int issued = fixture.transport.started.size();
        fixture.routes.route(handle, player, TARGET);
        assertEquals(issued + 1, fixture.transport.started.size(),
                "a throwing teardown must not wedge the connection's routing");
        fixture.transport.finishLatest(SUCCESS);
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /** A callback that answers after its attempt was settled must change nothing. */
    @Test
    void aLateCallbackAfterSettlementDoesNotDisturbTheNextDecision() {
        FakeConnection player = new FakeConnection(uuidOf("Aniela"), "Aniela");
        ConnectionHandle handle = joined("Aniela", player);

        fixture.routes.route(handle, player, LIMBO);
        AuthFlowFixture.ControllableTransport.Started stuck = fixture.transport.latest();
        fixture.routeScheduler.runLongestPending(); // the watchdog closes the attempt out
        fixture.routeScheduler.runPending();        // ...and the retry issues a fresh one

        CompletionStage<RouteCoordinator.RouteResult> next =
                fixture.routes.route(handle, player, TARGET);
        int issued = fixture.transport.started.size();

        stuck.future().complete(SUCCESS); // the abandoned attempt finally answers

        assertNull(next.toCompletableFuture().getNow(null), "the live decision is untouched");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle));
        assertEquals(issued, fixture.transport.started.size(), "and nothing new was issued");

        // The limbo transfer that was still running finishes first, which then issues the target
        // one the newer decision asked for; both have to land before it is settled.
        fixture.transport.finishLatest(SUCCESS);
        assertEquals(TARGET, fixture.transport.latest().destination());
        fixture.transport.finishLatest(SUCCESS);
        assertEquals(RouteCoordinator.RouteResult.REACHED, next.toCompletableFuture().getNow(null),
                "the live decision then settles normally");
    }

    /**
     * The race the terminal snapshot used to lose. The last limbo attempt has failed and decision A
     * has been taken out of the state; before its fail-closed teardown runs, a late transfer lands
     * the player in the limbo for real and the arrival is confirmed.
     *
     * <p>The teardown re-reads the confirmed backend under the route lock instead of trusting the
     * snapshot it was handed, so it stands down: closing a player who is sitting in exactly the
     * server we wanted them in would be the worst possible outcome of a failed transfer.
     */
    @Test
    void aConfirmedLimboArrivalBeatsTheFailClosedTeardown() {
        FakeConnection player = new FakeConnection(uuidOf("Bogumila"), "Bogumila");
        ConnectionHandle handle = joined("Bogumila", player);
        assertFalse(handle.isAuthenticated(), "precondition: unauthenticated");

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(handle, player, LIMBO);

        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();

        // Fires between the routing lock being released and the teardown running.
        AtomicBoolean confirmed = new AtomicBoolean();
        fixture.routeScheduler.onNextCancel = () -> {
            arrive(handle, player, LIMBO);
            confirmed.set(true);
        };
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertTrue(confirmed.get(), "precondition: the arrival really landed inside the window");
        assertTrue(fixture.transport.disconnected.isEmpty(),
                "a player confirmed in the limbo must never be closed by a stale teardown");
        assertTrue(fixture.transport.disconnectReasons.isEmpty());
        assertEquals(RouteCoordinator.RouteResult.ALREADY_THERE,
                decision.toCompletableFuture().getNow(null),
                "and the result says where the player really is");
        assertFalse(fixture.routes.hasPendingWork(handle));
        assertTrue(pendingRetries().isEmpty());

        // A late callback or watchdog from the abandoned attempts changes nothing.
        fixture.transport.started.forEach(t -> t.future().complete(SUCCESS));
        fixture.routeScheduler.runPending();
        assertTrue(fixture.transport.disconnected.isEmpty());
        assertEquals(RouteCoordinator.RouteResult.ALREADY_THERE,
                decision.toCompletableFuture().getNow(null), "still exactly one completion");
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /** The mirror: the teardown wins, and the arrival that follows it revives nothing. */
    @Test
    void aFailClosedTeardownThatWinsIsNotUndoneByALaterArrival() {
        FakeConnection player = new FakeConnection(uuidOf("Cyprian2"), "Cyprian2");
        ConnectionHandle handle = joined("Cyprian2", player);

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(handle, player, LIMBO);
        failEveryAttempt(SERVER_DISCONNECTED);

        assertEquals(List.of(player), fixture.transport.disconnected, "exactly one disconnect");
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                decision.toCompletableFuture().getNow(null));

        int issued = fixture.transport.started.size();
        arrive(handle, player, LIMBO); // the arrival turns up afterwards

        assertEquals(List.of(player), fixture.transport.disconnected, "still exactly one disconnect");
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                decision.toCompletableFuture().getNow(null), "and exactly one completion");
        assertEquals(issued, fixture.transport.started.size(), "no route may be revived");
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    // ------------------------------- a newer operation is not evidence that anybody is safe

    /**
     * Sets a player up authenticated and confirmed on the target, force-logs them out, and burns
     * two of the three limbo attempts. The third is left for the caller to fail.
     *
     * @return the decision the force-logout created
     */
    private CompletionStage<RouteCoordinator.RouteResult> demotedWithTwoFailedLimboAttempts(
            String username, FakeConnection player, ConnectionHandle handle) {
        login(handle, player);
        fixture.transport.finishLatest(SUCCESS);
        arrive(handle, player, TARGET);
        fixture.reset();

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.apply(forced.playerEffect(), player).routing().orElseThrow();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        return decision;
    }

    /**
     * The security hole this closes. A wrong password mints a fresh operation and authenticates
     * nobody. Gating the fail-closed teardown on "is my operation still the newest" therefore let a
     * failed login switch the guard off and leave an unauthenticated player sitting on the target.
     */
    @Test
    void aFailedLoginMustNotDisableTheFailClosedGuard() {
        FakeConnection player = new FakeConnection(uuidOf("Bartlomiej"), "Bartlomiej");
        ConnectionHandle handle = joined("Bartlomiej", player);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                demotedWithTwoFailedLimboAttempts("Bartlomiej", player, handle);

        long before = handle.currentOperation();
        int transfersBefore = fixture.transport.started.size();
        assertTrue(fixture.apply(fixture.flow.login(handle, "wrong-password"), player).applied(),
                "the failed login is a real, fully applied operation");

        assertTrue(handle.currentOperation() > before, "it did take a newer operation id");
        assertFalse(handle.isAuthenticated(), "but it authenticated nobody");
        assertEquals(LIMBO, fixture.routes.desiredRoute(handle),
                "and asked for no route of its own - the logout's is still the only one");
        assertEquals(transfersBefore, fixture.transport.started.size(),
                "so nothing new is delivering this player anywhere");

        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertEquals(List.of(player), fixture.transport.disconnected,
                "an unauthenticated player on the target must be closed, whatever ran in between");
        assertEquals(List.of("disconnect.limbo-unavailable"), fixture.transport.disconnectReasons);
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                decision.toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /** The same for a lookup that finds no account: newer operation, still nobody authenticated. */
    @Test
    void anAccountNotFoundLoginMustNotDisableTheFailClosedGuard() {
        FakeConnection player = new FakeConnection(uuidOf("Celestyna"), "Celestyna");
        ConnectionHandle handle = joined("Celestyna", player);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                demotedWithTwoFailedLimboAttempts("Celestyna", player, handle);

        // The account disappears underneath them, so the login cannot even find it.
        fixture.backing.findByUsername("celestyna").ifPresent(a -> fixture.backing.delete(a.id()));
        long before = handle.currentOperation();
        assertEquals(Optional.of("login.not-registered"),
                fixture.flow.login(handle, "verylongpw").messageKey());
        assertTrue(handle.currentOperation() > before);
        assertFalse(handle.isAuthenticated());

        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertEquals(List.of(player), fixture.transport.disconnected,
                "a newer operation id is not evidence that anybody is safe");
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                decision.toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /** ...and a locked account, which is a third way to mint an operation without authenticating. */
    @Test
    void aLockedAccountLoginMustNotDisableTheFailClosedGuard() {
        FakeConnection player = new FakeConnection(uuidOf("Dionizy"), "Dionizy");
        ConnectionHandle handle = joined("Dionizy", player);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                demotedWithTwoFailedLimboAttempts("Dionizy", player, handle);

        fixture.backing.findByUsername("dionizy").ifPresent(a ->
                fixture.backing.updateFailedAttempts(a.id(), 99, System.currentTimeMillis() + 600_000L));
        long before = handle.currentOperation();
        assertEquals(Optional.of("login.account-locked"),
                fixture.flow.login(handle, "verylongpw").messageKey());
        assertTrue(handle.currentOperation() > before);
        assertFalse(handle.isAuthenticated());

        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertEquals(List.of(player), fixture.transport.disconnected);
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                decision.toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /**
     * The mirror: a login that really authenticates does protect the connection, and the target
     * decision it created stays whole - the old terminal path may neither close the player nor
     * settle or clear the newer decision.
     */
    @Test
    void aSuccessfulLoginProtectsTheConnectionAndKeepsItsOwnDecision() {
        FakeConnection player = new FakeConnection(uuidOf("Eleonora"), "Eleonora");
        ConnectionHandle handle = joined("Eleonora", player);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                demotedWithTwoFailedLimboAttempts("Eleonora", player, handle);

        CompletionStage<RouteCoordinator.RouteResult> newer =
                fixture.apply(fixture.flow.login(handle, "verylongpw"), player).routing().orElseThrow();
        assertTrue(handle.isAuthenticated());

        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertTrue(fixture.transport.disconnected.isEmpty(),
                "a genuinely authenticated connection is never closed by an old decision");
        assertEquals(RouteCoordinator.RouteResult.SUPERSEDED,
                decision.toCompletableFuture().getNow(null),
                "the login's own route replaced the logout's, which is the honest answer");
        assertNull(newer.toCompletableFuture().getNow(null),
                "and the newer decision is neither settled nor cleared by it");
        assertEquals(TARGET, fixture.routes.desiredRoute(handle));

        fixture.transport.finishLatest(SUCCESS);
        assertEquals(RouteCoordinator.RouteResult.REACHED, newer.toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    /**
     * A newer limbo decision that is actively working takes responsibility, so the old terminal path
     * stands down as SUPERSEDED. When that newer decision runs out in turn, <em>its</em> terminal
     * path is the one that closes the connection - the guard is deferred, never dropped.
     */
    @Test
    void aNewerLimboRecoveryTakesOverAndStillEndsFailClosed() {
        FakeConnection player = new FakeConnection(uuidOf("Fryderyk"), "Fryderyk");
        ConnectionHandle handle = joined("Fryderyk", player);
        CompletionStage<RouteCoordinator.RouteResult> decision =
                demotedWithTwoFailedLimboAttempts("Fryderyk", player, handle);

        // A fresh limbo decision is registered inside the old one's terminal window.
        AtomicReference<CompletionStage<RouteCoordinator.RouteResult>> newer = new AtomicReference<>();
        fixture.routeScheduler.onNextCancel =
                () -> newer.compareAndSet(null, fixture.routes.route(handle, player, LIMBO));
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertNotNull(newer.get(), "precondition: the newer decision landed in the window");
        assertTrue(fixture.transport.disconnected.isEmpty(),
                "the newer recovery owns safe delivery, so nobody is closed yet");
        assertEquals(RouteCoordinator.RouteResult.SUPERSEDED,
                decision.toCompletableFuture().getNow(null));
        assertNull(newer.get().toCompletableFuture().getNow(null), "and it is still running");

        // It fails too, and its own terminal path closes the connection.
        failEveryAttempt(SERVER_DISCONNECTED);

        assertEquals(List.of(player), fixture.transport.disconnected,
                "the guard was deferred to the newer decision, not dropped");
        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED,
                newer.get().toCompletableFuture().getNow(null));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    // ---------------------------------------- a connection that goes is reported as gone

    @Test
    void aDisconnectInTheTerminalWindowIsReportedAsConnectionGone() {
        FakeConnection player = new FakeConnection(uuidOf("Gabriel"), "Gabriel");
        ConnectionHandle handle = joined("Gabriel", player);

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(handle, player, LIMBO);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();

        fixture.routeScheduler.onNextCancel = () -> fixture.disconnect(player);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertEquals(RouteCoordinator.RouteResult.CONNECTION_GONE,
                decision.toCompletableFuture().getNow(null),
                "a connection that ended is gone, not 'still open'");
        assertTrue(fixture.transport.disconnected.isEmpty(), "no second teardown of a dead socket");
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    @Test
    void aReconnectInTheTerminalWindowIsReportedAsConnectionGoneAndSparesTheNewSocket() {
        UUID uuid = uuidOf("Hieronim");
        FakeConnection first = new FakeConnection(uuid, "Hieronim");
        ConnectionHandle oldHandle = joined("Hieronim", first);
        FakeConnection second = new FakeConnection(uuid, "Hieronim");
        AtomicReference<ConnectionHandle> replacement = new AtomicReference<>();

        CompletionStage<RouteCoordinator.RouteResult> decision =
                fixture.routes.route(oldHandle, first, LIMBO);
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();

        fixture.routeScheduler.onNextCancel =
                () -> replacement.compareAndSet(null, fixture.connect(second));
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertNotNull(replacement.get());
        assertEquals(RouteCoordinator.RouteResult.CONNECTION_GONE,
                decision.toCompletableFuture().getNow(null));
        assertTrue(fixture.transport.disconnected.isEmpty(),
                "the replacement socket must never be closed by the old decision");
        assertFalse(fixture.routes.hasPendingWork(replacement.get()), "and inherits no state");
        assertTrue(fixture.transport.started.stream().noneMatch(t -> t.connection() == second));
    }

    // ------------------------------------------------------------------ isolation and cleanup

    @Test
    void transfersForDifferentUuidsRunInParallel() {
        FakeConnection one = new FakeConnection(uuidOf("Marian"), "Marian");
        FakeConnection two = new FakeConnection(uuidOf("Natalia"), "Natalia");
        ConnectionHandle handleOne = joined("Marian", one);
        ConnectionHandle handleTwo = joined("Natalia", two);

        login(handleOne, one);
        login(handleTwo, two);

        assertEquals(2, fixture.transport.inFlight(),
                "one connection's transfer must never hold another connection's back");
        assertTrue(fixture.routes.isTransferInFlight(handleOne));
        assertTrue(fixture.routes.isTransferInFlight(handleTwo));

        fixture.transport.finish(0, SUCCESS);
        assertTrue(fixture.routes.isTransferInFlight(handleTwo), "and finishing one leaves the other running");
        fixture.transport.finish(1, SUCCESS);
        assertEquals(0, fixture.transport.inFlight());
    }

    @Test
    void aChurnOfPlayersLeavesNoRegistryRoutingOrTransferStateBehind() {
        int players = 60;
        for (int i = 0; i < players; i++) {
            FakeConnection player = FakeConnection.of("Churn-" + i);
            ConnectionHandle handle = fixture.connect(player);
            fixture.joinCracked(handle);
            // The per-IP account cap means only the first few registrations succeed and route; the
            // rest still allocate a handle, a commit slot and a routing state, which is what has to
            // be gone again afterwards.
            fixture.apply(fixture.flow.register(handle, "verylongpw", "verylongpw"), player);
            // Half of them leave with a transfer still running, half after it landed.
            if (i % 2 == 0) {
                fixture.transport.finishLatest(SUCCESS);
                fixture.disconnect(player);
            } else {
                fixture.disconnect(player);
                fixture.transport.finishLatest(SUCCESS);
            }
            assertNull(fixture.routes.desiredRoute(handle), "player " + i + " left nothing pending");
            assertFalse(fixture.routes.isTransferInFlight(handle));
        }

        assertEquals(0, fixture.registry.size(), "every connection must be released");
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
        assertEquals(0, fixture.transport.inFlight(), "no transfer may be left hanging");
        assertTrue(fixture.transport.started.size() >= 1, "the churn really did route somebody");
        assertTrue(fixture.routeScheduler.pending().isEmpty(),
                "no retry timer and no watchdog may still be armed");
        assertFalse(fixture.registry.hasObservationScope(),
                "and this thread must hold no failure-observation scope");
    }
}
