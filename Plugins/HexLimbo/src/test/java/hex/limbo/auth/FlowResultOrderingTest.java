package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player-facing half of the commit order.
 *
 * <p>A flow releases its commit section when it <em>returns</em>; the message and the routing it
 * decided on are applied afterwards. Another operation on the same connection can commit and be
 * applied in that gap, and delivering the older result then contradicts what the player has already
 * been shown - an old {@code /login} announcing success and pulling them out of the limbo a later
 * {@code /logout} just put them in, or an old {@code /logout} dropping an authenticated player back
 * into it.
 *
 * <p>Every test here pauses a worker <b>between the flow returning and the result being applied</b>
 * with a plain {@link CountDownLatch}, lets the competing operation complete in full, and then
 * releases the straggler. The invariant under test:
 *
 * <blockquote>if operation B committed after operation A, a late result from A can no longer send a
 * message or perform any routing once B has taken effect.</blockquote>
 *
 * <p>The apply path is the production one: {@link FlowResultApplier} decides whether a result may
 * still run, and {@code AuthFlowFixture} only substitutes the Velocity calls at the very edge,
 * exactly as {@code FlowCommandSupport} supplies them for a real {@code Player}.
 */
class FlowResultOrderingTest {

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

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(thread.isAlive(), thread.getName() + " never finished");
    }

    private static ConnectionRegistry.ApplyOutcome outcomeOf(FlowResultApplier.Application application) {
        return application.outcome();
    }

    private static UUID uuidOf(String username) {
        return UUID.nameUUIDFromBytes(("u:" + username).getBytes());
    }

    /** An authenticated connection for a seeded account, as a session auto-login produces. */
    private ConnectionHandle authenticated(String username, FakeConnection player) {
        fixture.seedAccount(username, "verylongpw");
        fixture.sessions.sessionValid = true;
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        return handle;
    }

    /**
     * Runs {@code flow} on a worker, holds its result until {@code release} opens, and then applies
     * it through the production applier. This is the "paused before apply" window.
     */
    private Thread deferApply(
            String name,
            java.util.function.Supplier<AuthFlow.Result> flow,
            FakeConnection player,
            CountDownLatch returned,
            CountDownLatch release,
            AtomicBoolean applied
    ) {
        return run(name, () -> {
            AuthFlow.Result result = flow.get();
            returned.countDown();
            await(release);
            applied.set(fixture.apply(result, player).applied());
        });
    }

    // ------------------------------------------------ case A: old login vs newer logout

    @Test
    void anOldLoginResultCannotBeAppliedAfterALaterLogout() throws InterruptedException {
        fixture.seedAccount("Kamil", "verylongpw");
        FakeConnection player = new FakeConnection(uuidOf("Kamil"), "Kamil");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch loginReturned = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AtomicBoolean loginApplied = new AtomicBoolean(true);
        Thread login = deferApply("login", () -> fixture.flow.login(handle, "verylongpw"),
                player, loginReturned, releaseLogin, loginApplied);
        await(loginReturned); // the login has committed but has not touched the player yet

        // The logout the player typed afterwards commits and is applied in full.
        AuthFlow.Result logoutResult = fixture.flow.logout(handle);
        assertTrue(fixture.apply(logoutResult, player).applied(), "the later logout is the one that counts");

        releaseLogin.countDown();
        join(login);

        AuthFlowFixture.RecordingEffects effects = fixture.effectsFor(player);
        assertFalse(loginApplied.get(), "an overtaken login result must not be applied");
        assertEquals(List.of("logout.success"), effects.messageKeys,
                "no login confirmation may follow the logout");
        assertEquals(List.of("LIMBO"), effects.actions,
                "and certainly no stale trip to the target server");
        assertEquals(AuthState.Stage.AWAITING_LOGIN, handle.authState().orElseThrow().stage());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------ case B: old logout vs newer login

    @Test
    void anOldLogoutResultCannotBeAppliedAfterALaterLogin() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Lidia"), "Lidia");
        ConnectionHandle handle = authenticated("Lidia", player);

        CountDownLatch logoutReturned = new CountDownLatch(1);
        CountDownLatch releaseLogout = new CountDownLatch(1);
        AtomicBoolean logoutApplied = new AtomicBoolean(true);
        Thread logout = deferApply("logout", () -> fixture.flow.logout(handle),
                player, logoutReturned, releaseLogout, logoutApplied);
        await(logoutReturned);

        // The player logs straight back in; that operation commits and is applied.
        AuthFlow.Result loginResult = fixture.flow.login(handle, "verylongpw");
        assertTrue(fixture.apply(loginResult, player).applied());

        releaseLogout.countDown();
        join(logout);

        AuthFlowFixture.RecordingEffects effects = fixture.effectsFor(player);
        assertFalse(logoutApplied.get(), "an overtaken logout result must not be applied");
        assertEquals(List.of("login.success"), effects.messageKeys,
                "the authenticated player must not be told they logged out");
        assertEquals(List.of("TARGET"), effects.actions,
                "and must not be dropped back into the limbo, where they would get no prompt");
        assertTrue(handle.isAuthenticated());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------------- the same race via forcelogout

    @Test
    void anOldAdminForceLogoutResultCannotBeAppliedAfterALaterLogin() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Marta"), "Marta");
        ConnectionHandle handle = authenticated("Marta", player);

        CountDownLatch forcedReturned = new CountDownLatch(1);
        CountDownLatch releaseForced = new CountDownLatch(1);
        AtomicBoolean forcedApplied = new AtomicBoolean(true);
        AtomicReference<AuthService.LogoutOutcome> outcome = new AtomicReference<>();
        Thread forced = run("forcelogout", () -> {
            AuthFlow.ForcedLogout result = fixture.flow.forceLogout(handle);
            outcome.set(result.outcome());
            forcedReturned.countDown();
            await(releaseForced);
            forcedApplied.set(fixture.apply(result.playerEffect(), player).applied());
        });
        await(forcedReturned);

        AuthFlow.Result loginResult = fixture.flow.login(handle, "verylongpw");
        assertTrue(fixture.apply(loginResult, player).applied());

        releaseForced.countDown();
        join(forced);

        AuthFlowFixture.RecordingEffects effects = fixture.effectsFor(player);
        assertEquals(AuthService.LogoutOutcome.SUCCESS, outcome.get(), "the forced logout did happen");
        assertFalse(forcedApplied.get(), "but its routing was overtaken and must be dropped");
        assertEquals(List.of("TARGET"), effects.actions,
                "the player who logged back in stays on the target server");
        assertEquals(List.of("login.success"), effects.messageKeys);
        assertTrue(handle.isAuthenticated());
    }

    // ------------------------------------------------------ disconnect and reconnect windows

    @Test
    void aDisconnectBetweenTheFlowAndTheApplyKeepsTheResultSilent() throws InterruptedException {
        fixture.seedAccount("Norbert", "verylongpw");
        FakeConnection player = new FakeConnection(uuidOf("Norbert"), "Norbert");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean(true);
        Thread login = deferApply("login", () -> fixture.flow.login(handle, "verylongpw"),
                player, returned, release, applied);
        await(returned);

        fixture.disconnect(player);
        release.countDown();
        join(login);

        assertFalse(applied.get());
        assertTrue(fixture.effectsFor(player).isSilent(), "a socket that left gets nothing at all");
        assertTrue(player.messages.isEmpty());
        // The commit itself stands - it really happened - only the player-facing half is dropped.
        assertTrue(handle.isAuthenticated());
        assertTrue(fixture.audit.has("LOGIN"));
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void aReconnectBetweenTheFlowAndTheApplyLeavesBothConnectionsAlone() throws InterruptedException {
        fixture.seedAccount("Olaf", "verylongpw");
        UUID uuid = uuidOf("Olaf");
        FakeConnection first = new FakeConnection(uuid, "Olaf");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        fixture.reset();

        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean(true);
        Thread login = deferApply("login", () -> fixture.flow.login(oldHandle, "verylongpw"),
                first, returned, release, applied);
        await(returned);

        // The player reconnects; B owns the UUID now.
        FakeConnection second = new FakeConnection(uuid, "Olaf");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        release.countDown();
        join(login);

        assertFalse(applied.get());
        assertTrue(fixture.effectsFor(first).isSilent(), "the displaced socket stays completely silent");
        assertTrue(fixture.effectsFor(second).isSilent(), "and the replacement is not touched either");
        assertTrue(second.messages.isEmpty());
        assertFalse(newHandle.isAuthenticated(), "the new connection still has to log in itself");
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------------------------------ /premium

    @Test
    void anAlreadyPendingPremiumReplyIsSuppressedForADepartedConnection() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Patryk"), "Patryk");
        ConnectionHandle handle = authenticated("Patryk", player);
        // First request puts the account into PENDING_MIGRATION.
        assertTrue(fixture.apply(fixture.flow.requestPremiumMigration(handle), player).applied());
        fixture.reset();

        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean(true);
        Thread premium = deferApply("premium", () -> fixture.flow.requestPremiumMigration(handle),
                player, returned, release, applied);
        await(returned);

        fixture.disconnect(player);
        release.countDown();
        join(premium);

        assertFalse(applied.get(), "the early already-requested reply is not exempt from the gate");
        assertTrue(fixture.effectsFor(player).isSilent());
        assertEquals(AccountType.PENDING_MIGRATION,
                fixture.backing.findByUuid(handle.uuid()).orElseThrow().accountType());
        assertFalse(fixture.audit.has("PREMIUM_REQUEST"), "and nothing was written a second time");
    }

    @Test
    void anAlreadyPremiumReplyIsSuppressedWhenANewConnectionSupersedes() throws InterruptedException {
        UUID uuid = uuidOf("Renata");
        FakeConnection first = new FakeConnection(uuid, "Renata");
        ConnectionHandle handle = authenticated("Renata", first);
        fixture.backing.findByUuid(uuid).ifPresent(account ->
                fixture.backing.updateAccountType(account.id(), AccountType.PREMIUM));

        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean applied = new AtomicBoolean(true);
        Thread premium = deferApply("premium", () -> fixture.flow.requestPremiumMigration(handle),
                first, returned, release, applied);
        await(returned);

        FakeConnection second = new FakeConnection(uuid, "Renata");
        fixture.connect(second);

        release.countDown();
        join(premium);

        assertFalse(applied.get());
        assertTrue(fixture.effectsFor(first).isSilent());
        assertTrue(fixture.effectsFor(second).isSilent(), "the replacement must not inherit the reply");
    }

    /** The mirror image throughout: with nothing racing it, a result is applied normally. */
    @Test
    void anUncontestedResultIsAppliedInFull() {
        fixture.seedAccount("Sylwia", "verylongpw");
        FakeConnection player = new FakeConnection(uuidOf("Sylwia"), "Sylwia");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();

        assertTrue(fixture.apply(fixture.flow.login(handle, "verylongpw"), player).applied());

        AuthFlowFixture.RecordingEffects effects = fixture.effectsFor(player);
        assertEquals(List.of("login.success"), effects.messageKeys);
        assertEquals(List.of("TARGET"), effects.actions);
        assertEquals("Logowanie zakończone pomyślnie.", player.lastMessage());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    // ------------------------------------------- an internal error is ordered like any result

    /**
     * A flow that throws has already released its commit section, so a newer operation can commit
     * and be applied before the catch block runs. The stale {@code error.internal} line must not
     * land after it.
     *
     * <p>The supplier is held open <em>after</em> the flow threw, which is exactly that window: the
     * lease is gone, the exception has not been caught yet.
     */
    @Test
    void anInternalErrorIsSuppressedWhenALaterOperationOvertookIt() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Tymon"), "Tymon");
        ConnectionHandle handle = authenticated("Tymon", player);
        fixture.repository.failAt("updatePasswordHash", new IllegalStateException("db down"));

        CountDownLatch threw = new CountDownLatch(1);
        CountDownLatch mayReport = new CountDownLatch(1);
        AtomicReference<ConnectionRegistry.ApplyOutcome> reported = new AtomicReference<>();
        Thread worker = run("changepassword", () -> reported.set(outcomeOf(fixture.execute(
                "/changepassword", handle, player, () -> {
                    try {
                        return fixture.flow.changePassword(handle, "verylongpw", "newlongpassword");
                    } finally {
                        threw.countDown();
                        await(mayReport);
                    }
                }))));
        await(threw);

        // A logout commits and is applied while the failed attempt is still unwinding.
        assertTrue(fixture.apply(fixture.flow.logout(handle), player).applied());

        mayReport.countDown();
        join(worker);

        assertEquals(ConnectionRegistry.ApplyOutcome.OVERTAKEN, reported.get());
        AuthFlowFixture.RecordingEffects effects = fixture.effectsFor(player);
        assertFalse(effects.messageKeys.contains("error.internal"),
                "a stale failure must not be reported after a newer operation took effect");
        assertEquals(List.of("logout.success"), effects.messageKeys);
        assertEquals(0, fixture.registry.commitSlotsTracked(), "and the failure released every slot");
    }

    /** The mirror image: with nothing racing it, the player is told about the failure. */
    @Test
    void anInternalErrorIsReportedWhenNothingOvertookIt() {
        FakeConnection player = new FakeConnection(uuidOf("Urban"), "Urban");
        ConnectionHandle handle = authenticated("Urban", player);
        fixture.repository.failAt("updatePasswordHash", new IllegalStateException("db down"));

        ConnectionRegistry.ApplyOutcome reported = fixture.execute("/changepassword", handle, player,
                () -> fixture.flow.changePassword(handle, "verylongpw", "newlongpassword")).outcome();

        assertEquals(ConnectionRegistry.ApplyOutcome.APPLIED, reported);
        assertEquals(List.of("error.internal"), fixture.effectsFor(player).messageKeys);
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void anInternalErrorStaysSilentWhenTheConnectionLeftOrWasReplaced() throws InterruptedException {
        UUID uuid = uuidOf("Wiesia");
        FakeConnection first = new FakeConnection(uuid, "Wiesia");
        ConnectionHandle handle = authenticated("Wiesia", first);
        fixture.repository.failAt("updatePasswordHash", new IllegalStateException("db down"));

        CountDownLatch threw = new CountDownLatch(1);
        CountDownLatch mayReport = new CountDownLatch(1);
        AtomicReference<ConnectionRegistry.ApplyOutcome> reported = new AtomicReference<>();
        Thread worker = run("changepassword", () -> reported.set(outcomeOf(fixture.execute(
                "/changepassword", handle, first, () -> {
                    try {
                        return fixture.flow.changePassword(handle, "verylongpw", "newlongpassword");
                    } finally {
                        threw.countDown();
                        await(mayReport);
                    }
                }))));
        await(threw);

        // The player reconnects while the failure is unwinding.
        FakeConnection second = new FakeConnection(uuid, "Wiesia");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);

        mayReport.countDown();
        join(worker);

        assertEquals(ConnectionRegistry.ApplyOutcome.STALE_CONNECTION, reported.get());
        assertTrue(fixture.effectsFor(first).isSilent(), "the departed socket hears nothing");
        assertTrue(fixture.effectsFor(second).isSilent(), "and the replacement is not blamed for it");
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /**
     * The case a predicted operation id got wrong. A is held <em>before</em> it reaches the commit
     * slot, B slips in and takes the next id, and only then does A get its own - a later one - and
     * throw. A is now the connection's newest operation, so its failure is the one that counts and
     * has to be shown. A guess made before A started would have named B's id and stayed silent.
     */
    @Test
    void aFailureThatEndsUpNewestIsReportedEvenThoughAnotherOperationWentFirst() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Alicja");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        fixture.repository.failAt("create", new IllegalStateException("db down"));

        // /register consults Mojang before it takes the slot; that is where A waits.
        CountDownLatch insideResolver = new CountDownLatch(1);
        CountDownLatch mayProceed = new CountDownLatch(1);
        fixture.premiumResolver = name -> {
            insideResolver.countDown();
            await(mayProceed);
            return hex.limbo.premium.PremiumResolver.Result.notPremium();
        };

        AtomicReference<ConnectionRegistry.ApplyOutcome> reported = new AtomicReference<>();
        Thread a = run("register", () -> reported.set(outcomeOf(fixture.execute(
                "/register", handle, player, () -> fixture.flow.register(handle, "verylongpw", "verylongpw")))));
        await(insideResolver);

        // B takes the next operation id while A is still outside the slot.
        long before = handle.currentOperation();
        fixture.apply(fixture.flow.requestPremiumMigration(handle), player);
        assertEquals(before + 1, handle.currentOperation(), "precondition: B really took the next id");

        mayProceed.countDown();
        join(a);

        assertEquals(ConnectionRegistry.ApplyOutcome.APPLIED, reported.get(),
                "A ran as the newest operation, so its failure is the one that counts");
        assertTrue(fixture.effectsFor(player).messageKeys.contains("error.internal"));
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /**
     * Two attempts in flight at once must each answer for themselves. The first enters the slot and
     * fails; the second queues behind it, enters, and fails too. Because they ran as different
     * operations exactly one of them - the later - is the connection's newest failure and is shown.
     *
     * <p>An id guessed before either started would have named the same operation for both, and
     * neither would have matched by the time they reported.
     */
    @Test
    void twoConcurrentAttemptsDoNotShareAStampAndOnlyTheNewestFailureIsShown() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Bartosz"), "Bartosz");
        ConnectionHandle handle = authenticated("Bartosz", player);
        fixture.repository.failAt("updatePasswordHash", new IllegalStateException("db down"));
        long before = handle.currentOperation();

        CountDownLatch firstThrew = new CountDownLatch(1);
        CountDownLatch firstMayReport = new CountDownLatch(1);
        AtomicReference<ConnectionRegistry.ApplyOutcome> first = new AtomicReference<>();
        Thread one = run("cpw-1", () -> first.set(outcomeOf(fixture.execute(
                "/changepassword", handle, player, () -> {
                    try {
                        return fixture.flow.changePassword(handle, "verylongpw", "newlongpassword");
                    } finally {
                        firstThrew.countDown();
                        await(firstMayReport);
                    }
                }))));
        await(firstThrew); // the first attempt has run as its own operation and failed

        // The second attempt runs to completion while the first is still unwinding.
        ConnectionRegistry.ApplyOutcome second = outcomeOf(fixture.execute(
                "/changepassword", handle, player,
                () -> fixture.flow.changePassword(handle, "verylongpw", "newlongpassword")));

        firstMayReport.countDown();
        join(one);

        assertEquals(before + 2, handle.currentOperation(),
                "two attempts, two operation ids: they cannot have shared one");
        assertEquals(ConnectionRegistry.ApplyOutcome.APPLIED, second, "the later failure is the newest");
        assertEquals(ConnectionRegistry.ApplyOutcome.OVERTAKEN, first.get(), "the earlier one was overtaken");
        assertEquals(1, fixture.effectsFor(player).messageKeys.stream()
                        .filter("error.internal"::equals).count(),
                "exactly one failure is shown; a shared guess would have shown none");
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /**
     * A flow can throw before it ever reaches a commit slot - a Mojang lookup in {@code /register}.
     * There is no operation to be ordered against, so the honest question is whether anything at all
     * happened to this connection meanwhile. Nothing did, so the player is told.
     */
    @Test
    void aFailureBeforeTheCommitSlotIsReportedOnAnUnchangedConnection() {
        FakeConnection player = FakeConnection.of("Celina");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        fixture.premiumResolver = name -> {
            throw new IllegalStateException("Mojang lookup blew up");
        };

        ConnectionRegistry.ApplyOutcome reported = outcomeOf(fixture.execute(
                "/register", handle, player, () -> fixture.flow.register(handle, "verylongpw", "verylongpw")));

        assertEquals(ConnectionRegistry.ApplyOutcome.APPLIED, reported,
                "nothing overtook it, so the player learns the command failed");
        assertEquals(List.of("error.internal"), fixture.effectsFor(player).messageKeys);
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    /** The same pre-slot failure is silent once the connection has gone. */
    @Test
    void aFailureBeforeTheCommitSlotIsSilentAfterADisconnect() {
        FakeConnection player = FakeConnection.of("Damroka");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.reset();
        fixture.premiumResolver = name -> {
            fixture.disconnect(player);
            throw new IllegalStateException("Mojang lookup blew up");
        };

        ConnectionRegistry.ApplyOutcome reported = outcomeOf(fixture.execute(
                "/register", handle, player, () -> fixture.flow.register(handle, "verylongpw", "verylongpw")));

        assertEquals(ConnectionRegistry.ApplyOutcome.STALE_CONNECTION, reported);
        assertTrue(fixture.effectsFor(player).isSilent());
    }

    /** ...and once a real operation on another thread has overtaken it. */
    @Test
    void aFailureBeforeTheCommitSlotIsSilentOnceSomethingElseHasHappened() throws InterruptedException {
        FakeConnection player = new FakeConnection(uuidOf("Emilia"), "Emilia");
        ConnectionHandle handle = authenticated("Emilia", player);

        CountDownLatch insideResolver = new CountDownLatch(1);
        CountDownLatch mayThrow = new CountDownLatch(1);
        fixture.premiumResolver = name -> {
            insideResolver.countDown();
            await(mayThrow);
            throw new IllegalStateException("Mojang lookup blew up");
        };

        AtomicReference<ConnectionRegistry.ApplyOutcome> reported = new AtomicReference<>();
        Thread worker = run("register", () -> reported.set(outcomeOf(fixture.execute(
                "/register", handle, player, () -> fixture.flow.register(handle, "verylongpw", "verylongpw")))));
        await(insideResolver);

        // A logout commits and is applied while the lookup is stuck.
        assertTrue(fixture.apply(fixture.flow.logout(handle), player).applied());

        mayThrow.countDown();
        join(worker);

        assertEquals(ConnectionRegistry.ApplyOutcome.OVERTAKEN, reported.get());
        assertFalse(fixture.effectsFor(player).messageKeys.contains("error.internal"));
        assertEquals(List.of("logout.success"), fixture.effectsFor(player).messageKeys);
    }

    // ------------------------------------------------------------------ isolation and cleanup

    /** Applying is per-UUID: two players must never wait on each other's effects. */
    @Test
    void appliesForDifferentUuidsRunInParallel() throws InterruptedException {
        fixture.seedAccount("Tomasz", "verylongpw");
        fixture.seedAccount("Urszula", "verylongpw");
        FakeConnection one = new FakeConnection(uuidOf("Tomasz"), "Tomasz");
        FakeConnection two = new FakeConnection(uuidOf("Urszula"), "Urszula");
        ConnectionHandle handleOne = fixture.connect(one);
        ConnectionHandle handleTwo = fixture.connect(two);
        fixture.joinCracked(handleOne);
        fixture.joinCracked(handleTwo);

        AuthFlow.Result first = fixture.flow.login(handleOne, "verylongpw");
        AuthFlow.Result second = fixture.flow.login(handleTwo, "verylongpw");

        // The latch only opens when both applies are inside their effects at the same time, which
        // cannot happen if the two UUIDs were ordered against each other.
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch mayFinish = new CountDownLatch(1);
        FlowResultApplier.Effects blocking = new FlowResultApplier.Effects() {
            @Override public void sendMessage(String key, Object[] args) {
                bothInside.countDown();
                await(mayFinish);
            }
            @Override public void disconnect(String key, Object[] args) { }
            @Override public CompletionStage<RouteCoordinator.RouteResult> sendToTarget() { return null; }
            @Override public CompletionStage<RouteCoordinator.RouteResult> sendToLimbo() { return null; }
        };

        Thread applyOne = run("apply-1",
                () -> FlowResultApplier.apply(fixture.registry, first, one, blocking));
        Thread applyTwo = run("apply-2",
                () -> FlowResultApplier.apply(fixture.registry, second, two, blocking));
        await(bothInside);
        mayFinish.countDown();
        join(applyOne);
        join(applyTwo);

        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void anExceptionInsideTheOrderedApplyReleasesTheSlot() {
        fixture.seedAccount("Wanda", "verylongpw");
        FakeConnection player = new FakeConnection(uuidOf("Wanda"), "Wanda");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        AuthFlow.Result result = fixture.flow.login(handle, "verylongpw");

        FlowResultApplier.Effects exploding = new FlowResultApplier.Effects() {
            @Override public void sendMessage(String key, Object[] args) {
                throw new IllegalStateException("the proxy blew up mid-send");
            }
            @Override public void disconnect(String key, Object[] args) { }
            @Override public CompletionStage<RouteCoordinator.RouteResult> sendToTarget() { return null; }
            @Override public CompletionStage<RouteCoordinator.RouteResult> sendToLimbo() { return null; }
        };

        assertThrows(IllegalStateException.class,
                () -> FlowResultApplier.apply(fixture.registry, result, player, exploding));

        assertEquals(0, fixture.registry.commitSlotsTracked(), "the slot must be released anyway");
        assertEquals(0, fixture.registry.commitsInFlight());
        // ...and the connection is immediately usable again.
        assertTrue(fixture.flow.logout(handle).messageKey().isPresent());
    }

    /**
     * The revision lives on the handle, so it is released with the connection rather than collected
     * anywhere. After a churn of players nothing is left over, and results of the departed
     * connections stay inert.
     */
    @Test
    void nothingIsRetainedAfterAChurnOfPlayers() {
        int players = 60;
        for (int i = 0; i < players; i++) {
            FakeConnection player = FakeConnection.of("Churn-" + i);
            ConnectionHandle handle = fixture.connect(player);
            fixture.joinCracked(handle);
            AuthFlow.Result result = fixture.flow.register(handle, "verylongpw", "verylongpw");
            fixture.disconnect(player);

            assertFalse(fixture.apply(result, player).applied(), "a departed connection applies nothing");
            assertTrue(fixture.effectsFor(player).isSilent());
        }

        assertEquals(0, fixture.registry.size());
        assertEquals(0, fixture.registry.commitsInFlight());
        assertEquals(0, fixture.registry.commitSlotsTracked());
    }

    @Test
    void anUnstampedResultWithEffectsIsNeverApplied() {
        FakeConnection player = FakeConnection.of("Zenobia");
        fixture.connect(player);

        // Fail-closed: nothing reaches a player unless it can prove where it sits in the order.
        assertFalse(fixture.apply(
                AuthFlow.Result.messageAndRoute("login.success", AuthFlow.Routing.TARGET), player).applied());
        assertTrue(fixture.effectsFor(player).isSilent());
    }
}
