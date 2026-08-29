package hex.limbo.auth;

import hex.limbo.config.ConfigLoader;
import hex.limbo.config.MessagesConfig;
import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static hex.limbo.auth.RouteCoordinator.TransferStatus.ALREADY_CONNECTED;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.SERVER_DISCONNECTED;
import static hex.limbo.auth.RouteCoordinator.TransferStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /hexlimbo forcelogout} tells the staff member has to be what actually became of the
 * player - including whether they <em>arrived</em>.
 *
 * <p>Three separate things can go differently: the demotion, whether its visible effect was applied
 * in order at all, and whether the transfer that effect asked for reached the limbo. Issuing a
 * connection request is not arriving: it can sit queued behind another transfer, be superseded by a
 * login, fail every attempt, or belong to a socket that vanishes. Announcing
 * "Przekierowano X do serwera poczekalni." in any of those cases actively misinforms an operator
 * about where a player is.
 *
 * <p>Every transfer here therefore really is in flight until the test finishes it, and the report is
 * taken from the settled route result, exactly as the admin command does it.
 */
class ForceLogoutReportingTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    private static UUID uuidOf(String username) {
        return UUID.nameUUIDFromBytes(("u:" + username).getBytes());
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

    /** An authenticated cracked connection, as a session auto-login produces. */
    private ConnectionHandle authenticated(String username, FakeConnection player) {
        fixture.seedAccount(username, "verylongpw");
        fixture.sessions.sessionValid = true;
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        return handle;
    }

    /** The route result the admin command reports on, or {@code null} while it is still unsettled. */
    private static RouteCoordinator.RouteResult settled(FlowResultApplier.Application applied) {
        return applied.routing()
                .map(stage -> stage.toCompletableFuture().getNow(null))
                .orElse(null);
    }

    private static String report(AuthFlow.ForcedLogout forced, FlowResultApplier.Application applied) {
        return forced.staffMessageKey(applied.outcome(), settled(applied));
    }

    /** Drives every attempt of the outstanding decision to failure, running the retry timers. */
    private void failEveryAttempt() {
        for (int attempt = 0; attempt < RouteCoordinator.MAX_ATTEMPTS; attempt++) {
            fixture.transport.finishLatest(SERVER_DISCONNECTED);
            fixture.routeScheduler.runPending();
        }
    }

    // ------------------------------------------------------------------ confirmed arrival

    @Test
    void onlyAConfirmedArrivalIsReportedAsARedirect() {
        FakeConnection player = new FakeConnection(uuidOf("Ada"), "Ada");
        ConnectionHandle handle = authenticated("Ada", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        // The request has been issued and nothing more. Nothing may be claimed yet.
        assertTrue(applied.applied(), "the effect was applied in order");
        assertNull(settled(applied), "but the player has not arrived anywhere");
        assertNotEquals("admin.forcelogout.sent-limbo", report(forced, applied),
                "an issued request is not an arrival");

        fixture.transport.finishLatest(SUCCESS);

        assertEquals(RouteCoordinator.RouteResult.REACHED, settled(applied));
        assertEquals("admin.forcelogout.sent-limbo", report(forced, applied));
        assertFalse(handle.isAuthenticated());
    }

    @Test
    void anUnambiguousAlreadyConnectedCountsAsArrival() {
        FakeConnection player = new FakeConnection(uuidOf("Bronislawa"), "Bronislawa");
        ConnectionHandle handle = authenticated("Bronislawa", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);
        fixture.transport.finishLatest(ALREADY_CONNECTED);

        assertEquals(RouteCoordinator.RouteResult.ALREADY_THERE, settled(applied));
        assertEquals("admin.forcelogout.sent-limbo", report(forced, applied));
    }

    @Test
    void aPremiumForcedLogoutIsReportedAsAKickWithoutWaitingForAnyTransfer() {
        FakeConnection player = FakeConnection.of("Bogumil");
        ConnectionHandle handle = fixture.connect(player);
        fixture.flow.resolveJoin(handle, new AuthFlow.JoinRequest(true, false, "ip-hash"));
        fixture.reset();

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        assertTrue(applied.routing().isEmpty(), "a kick is not a transfer");
        assertEquals("admin.forcelogout.kicked-premium", report(forced, applied));
        assertEquals(List.of("DISCONNECT"), fixture.effectsFor(player).actions);
        assertTrue(handle.isAuthenticated(), "a premium account is kicked, never demoted");
    }

    // ------------------------------------------------------------------ queued behind a transfer

    @Test
    void aRouteQueuedBehindAnotherTransferIsNotReportedUntilItArrives() {
        FakeConnection player = new FakeConnection(uuidOf("Cezary"), "Cezary");
        ConnectionHandle handle = authenticated("Cezary", player);

        // A login transfer to the target is already running.
        fixture.apply(fixture.flow.login(handle, "verylongpw"), player);
        assertTrue(fixture.routes.isTransferInFlight(handle));

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        assertTrue(applied.applied());
        assertNull(settled(applied), "queued behind the target transfer, not arrived");
        assertNotEquals("admin.forcelogout.sent-limbo", report(forced, applied));

        // The target transfer finishes, the limbo one is issued, and only then is it true.
        fixture.transport.finish(0, SUCCESS);
        assertEquals(RouteCoordinator.Destination.LIMBO, fixture.transport.latest().destination());
        assertNull(settled(applied), "issued now, still not arrived");

        fixture.transport.finish(1, SUCCESS);
        assertEquals("admin.forcelogout.sent-limbo", report(forced, applied));
    }

    // ------------------------------------------------------------------ the dropped effects

    /**
     * The original regression: the effect never even reached the router because a login had already
     * been applied.
     */
    @Test
    void aForcedLogoutOvertakenBeforeItIsAppliedIsNotReportedAsARedirect() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Cecylia"), "Cecylia");
        ConnectionHandle handle = authenticated("Cecylia", player);

        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> reported = new AtomicReference<>();
        Thread staff = new Thread(() -> {
            AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
            returned.countDown();
            await(release);
            reported.set(report(forced, fixture.apply(forced.playerEffect(), player)));
        }, "forcelogout");
        staff.start();
        await(returned);

        assertTrue(fixture.apply(fixture.flow.login(handle, "verylongpw"), player).applied());

        release.countDown();
        staff.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(staff.isAlive());

        assertEquals("admin.forcelogout.overtaken", reported.get());
        assertEquals(List.of("TARGET"), fixture.effectsFor(player).actions,
                "the player is headed for the target, which is what the message has to reflect");
    }

    /** The subtler one: the effect was applied, but a login superseded the transfer mid-flight. */
    @Test
    void aRouteSupersededByALoginIsReportedAsOvertaken() {
        FakeConnection player = new FakeConnection(uuidOf("Dobromir"), "Dobromir");
        ConnectionHandle handle = authenticated("Dobromir", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);
        assertTrue(applied.applied());

        // The player logs back in while the limbo transfer is still running.
        assertTrue(fixture.apply(fixture.flow.login(handle, "verylongpw"), player).applied());

        assertEquals(RouteCoordinator.RouteResult.SUPERSEDED, settled(applied));
        assertEquals("admin.forcelogout.overtaken", report(forced, applied));
        assertNotEquals("admin.forcelogout.sent-limbo", report(forced, applied),
                "the player is on their way to the target, not to the limbo");
    }

    @Test
    void aRouteThatFailsEveryAttemptIsReportedAsAFailure() {
        FakeConnection player = new FakeConnection(uuidOf("Eustachy"), "Eustachy");
        ConnectionHandle handle = authenticated("Eustachy", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        for (int attempt = 0; attempt < RouteCoordinator.MAX_ATTEMPTS; attempt++) {
            fixture.transport.finishLatest(SERVER_DISCONNECTED);
            fixture.routeScheduler.runPending(); // the retry timer, if there is one left
        }

        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECTED, settled(applied));
        assertEquals("admin.forcelogout.route-failed", report(forced, applied));
        assertNotEquals("admin.forcelogout.sent-limbo", report(forced, applied));
        assertEquals(List.of(player), fixture.transport.disconnected,
                "an unauthenticated player who cannot be put in the limbo is closed fail-closed");
    }

    @Test
    void aConnectionThatEndsMidTransferIsReportedAsGone() {
        FakeConnection player = new FakeConnection(uuidOf("Franciszka"), "Franciszka");
        ConnectionHandle handle = authenticated("Franciszka", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);
        assertNull(settled(applied));

        fixture.disconnect(player);

        assertEquals(RouteCoordinator.RouteResult.CONNECTION_GONE, settled(applied));
        assertEquals("admin.forcelogout.connection-gone", report(forced, applied));
    }

    @Test
    void aForcedLogoutOnAConnectionThatAlreadyLeftIsReportedAsSuch() {
        FakeConnection player = new FakeConnection(uuidOf("Damian"), "Damian");
        ConnectionHandle handle = authenticated("Damian", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        fixture.disconnect(player);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        assertEquals("admin.forcelogout.connection-gone", report(forced, applied));
        assertTrue(fixture.effectsFor(player).isSilent(), "and nothing was done to the dead socket");
    }

    @Test
    void aConnectionWithNoAuthStateIsReportedAsSuchAndNothingIsRouted() {
        FakeConnection player = FakeConnection.of("Elwira");
        ConnectionHandle handle = fixture.connect(player); // registered, but never joined

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        assertEquals(AuthService.LogoutOutcome.NO_STATE, forced.outcome());
        assertEquals("admin.forcelogout.no-state", report(forced, applied));
        assertTrue(fixture.effectsFor(player).isSilent(), "no routing may be claimed or performed");
        assertEquals(0, fixture.transport.started.size());
    }

    /**
     * An authenticated player who is deliberately sitting in the limbo. The force-logout demotes
     * them, and nothing needs moving: no transfer is issued, nobody is closed, and the report says
     * so honestly rather than inventing a redirect or a disconnect.
     */
    @Test
    void aPlayerAlreadyConfirmedInTheLimboIsNeitherMovedNorClosed() {
        FakeConnection player = new FakeConnection(uuidOf("Genowefa"), "Genowefa");
        ConnectionHandle handle = authenticated("Genowefa", player);
        // /limbo put them there and the arrival was confirmed.
        fixture.arriveAt(handle, player, RouteCoordinator.Destination.LIMBO);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        assertEquals(AuthService.LogoutOutcome.SUCCESS, forced.outcome(), "the demotion happened");
        assertEquals(RouteCoordinator.RouteResult.ALREADY_THERE, settled(applied));
        assertEquals("admin.forcelogout.sent-limbo", report(forced, applied));
        assertTrue(fixture.transport.started.isEmpty(), "no redundant transfer may be issued");
        assertTrue(fixture.transport.disconnected.isEmpty(), "and nobody is closed");
        assertFalse(handle.isAuthenticated());
    }

    /**
     * A failed route that did <em>not</em> close the connection must not be reported as one that
     * did. Here the player logs back in while the transfer is failing, so the fail-closed teardown
     * is taken away from the stale decision.
     */
    @Test
    void aFailedRouteThatKeptTheConnectionDoesNotClaimADisconnect() {
        FakeConnection player = new FakeConnection(uuidOf("Hubert"), "Hubert");
        ConnectionHandle handle = authenticated("Hubert", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);
        // The player is back before the last attempt runs out.
        fixture.authService.attemptLogin(handle, "verylongpw");
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SERVER_DISCONNECTED);

        assertEquals(RouteCoordinator.RouteResult.FAILED_CONNECTION_KEPT, settled(applied));
        assertEquals("admin.forcelogout.route-failed-connected", report(forced, applied));
        assertNotEquals("admin.forcelogout.route-failed", report(forced, applied),
                "claiming a closed connection that is still open is the bug under test");
        assertTrue(fixture.transport.disconnected.isEmpty());
    }

    /**
     * A disconnect that throws leaves the connection in a state nobody can vouch for. The report
     * must claim neither "closed" nor "still open" - the two sentences that would both be guesses.
     */
    @Test
    void aThrowingDisconnectIsReportedAsAnUnknownConnectionState() {
        FakeConnection player = new FakeConnection(uuidOf("Jaroslaw"), "Jaroslaw");
        ConnectionHandle handle = authenticated("Jaroslaw", player);
        fixture.transport.disconnectFailure = new IllegalStateException("the socket blew up");

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);
        failEveryAttempt();

        assertEquals(RouteCoordinator.RouteResult.FAILED_DISCONNECT_UNKNOWN, settled(applied));
        assertEquals("admin.forcelogout.route-failed-unknown", report(forced, applied));
        assertNotEquals("admin.forcelogout.route-failed", report(forced, applied),
                "nothing proves the connection was closed");
        assertNotEquals("admin.forcelogout.route-failed-connected", report(forced, applied),
                "and nothing proves it is still open either");
    }

    /** The three terminal failures each own their sentence and never borrow another's. */
    @Test
    void theThreeTerminalFailuresNeverShareAMessage() {
        AuthFlow.ForcedLogout forced =
                new AuthFlow.ForcedLogout(AuthService.LogoutOutcome.SUCCESS,
                        java.util.Optional.of(new ConnectionRegistry.OperationStamp(
                                FakeConnection.of("Karolina").connect(fixture.registry), 1L)));

        String closed = forced.staffMessageKey(ConnectionRegistry.ApplyOutcome.APPLIED,
                RouteCoordinator.RouteResult.FAILED_DISCONNECTED);
        String kept = forced.staffMessageKey(ConnectionRegistry.ApplyOutcome.APPLIED,
                RouteCoordinator.RouteResult.FAILED_CONNECTION_KEPT);
        String unknown = forced.staffMessageKey(ConnectionRegistry.ApplyOutcome.APPLIED,
                RouteCoordinator.RouteResult.FAILED_DISCONNECT_UNKNOWN);

        assertEquals("admin.forcelogout.route-failed", closed);
        assertEquals("admin.forcelogout.route-failed-connected", kept);
        assertEquals("admin.forcelogout.route-failed-unknown", unknown);
        assertEquals(3, java.util.Set.of(closed, kept, unknown).size(),
                "three different terminal states, three different sentences");
        // ...and none of them is the arrival message.
        assertEquals("admin.forcelogout.sent-limbo", forced.staffMessageKey(
                ConnectionRegistry.ApplyOutcome.APPLIED, RouteCoordinator.RouteResult.REACHED));
    }

    /**
     * The transport callback and the watchdog can both fire for the same attempt. Only one may get
     * through, so the staff member gets exactly one message and the audit exactly one entry.
     */
    @Test
    void aCallbackRacingItsWatchdogStillSettlesTheDecisionExactlyOnce() {
        FakeConnection player = new FakeConnection(uuidOf("Ildefons"), "Ildefons");
        ConnectionHandle handle = authenticated("Ildefons", player);

        AuthFlow.ForcedLogout forced = fixture.flow.forceLogout(handle);
        FlowResultApplier.Application applied = fixture.apply(forced.playerEffect(), player);

        AtomicReference<RouteCoordinator.RouteResult> reports = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        applied.routing().orElseThrow().thenAccept(route -> {
            deliveries.incrementAndGet();
            reports.set(route);
        });

        // The watchdog fires and the real answer arrives afterwards, for the same attempt.
        fixture.routeScheduler.runLongestPending();
        fixture.transport.finishLatest(SUCCESS);
        // ...and the retry the watchdog armed also runs.
        fixture.routeScheduler.runPending();
        fixture.transport.finishLatest(SUCCESS);

        assertEquals(1, deliveries.get(), "exactly one report per force-logout");
        assertEquals(RouteCoordinator.RouteResult.REACHED, reports.get());
        assertEquals("admin.forcelogout.sent-limbo", report(forced, applied));
        assertFalse(fixture.routes.hasPendingWork(handle));
    }

    // ------------------------------------------------------------------ the messages themselves

    /**
     * The new keys have to resolve to real Polish text, not to their own name. That is what the
     * bundled-defaults overlay in {@link ConfigLoader} is for, and it has to hold for an operator
     * file written before these keys existed.
     */
    @Test
    void theNewStaffMessagesResolveEvenForAnOperatorFileThatPredatesThem(@TempDir Path dir) throws IOException {
        List<String> newKeys = List.of(
                "admin.forcelogout.overtaken",
                "admin.forcelogout.connection-gone",
                "admin.forcelogout.no-state",
                "admin.forcelogout.route-failed",
                "admin.forcelogout.route-failed-connected",
                "admin.forcelogout.route-failed-unknown");

        // An operator file that is current as far as it knows, but has never seen these keys.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("messages.yml"), """
                messages-version: 2
                login.success: "&aMoje wlasne powitanie."
                """, StandardCharsets.UTF_8);

        MessagesConfig messages = new ConfigLoader(dir, LoggerFactory.getLogger(getClass())).loadMessages();

        for (String key : newKeys) {
            String text = messages.raw(key);
            assertNotEquals(key, text, key + " must not render as its own name");
            assertTrue(text.contains("{0}"), key + " must name the player");
            assertTrue(text.startsWith("&"), key + " must carry the Hex colour scheme");
        }
        assertEquals("&aMoje wlasne powitanie.", messages.raw("login.success"),
                "and a value the operator set still wins over the bundled default");
    }
}
