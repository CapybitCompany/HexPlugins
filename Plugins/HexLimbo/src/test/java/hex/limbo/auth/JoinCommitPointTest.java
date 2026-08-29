package hex.limbo.auth;

import hex.limbo.account.Account;
import hex.limbo.account.AccountType;
import hex.limbo.prompt.AuthReason;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join pipeline's commit point.
 *
 * <p>{@code AuthService.trackConnection} - followed by {@code PromptService.markAuthenticated} on the
 * paths that authenticate straight away - decides whether the join happened. Everything that claims
 * a join succeeded has to come after it: {@code recordSuccessfulLogin}, {@code LOGIN_PREMIUM},
 * {@code LOGIN_SESSION}, {@code ADMIN_BYPASS_AUTH} and the queued greeting.
 *
 * <p>Account provisioning is deliberately <em>not</em> behind that point and is asserted as such:
 * a premium join has to create or migrate the row before anyone can decide anything, and that row
 * records an identity fact rather than a login. See {@link AuthFlow}'s class documentation.
 */
class JoinCommitPointTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();

    private AuthFlow.JoinRequest premium() {
        return new AuthFlow.JoinRequest(true, false, "ip-hash");
    }

    private AuthFlow.JoinRequest cracked() {
        return new AuthFlow.JoinRequest(false, false, "ip-hash");
    }

    private AuthFlow.JoinRequest adminBypass() {
        return new AuthFlow.JoinRequest(false, true, "ip-hash");
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

    // ------------------------------------------------------------------ happy paths

    @Test
    void aPremiumJoinAuthenticatesAndAuditsInThatOrder() {
        FakeConnection player = FakeConnection.of("Nadia");
        ConnectionHandle handle = fixture.connect(player);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, premium());

        assertFalse(result.denied());
        assertTrue(result.authenticated());
        assertTrue(handle.isAuthenticated());
        assertEquals(Optional.of(AuthReason.PREMIUM), fixture.prompts.pendingLobbyGreeting(handle));
        assertTrue(fixture.audit.has("PREMIUM_CREATED"), "provisioning is audited");
        assertTrue(fixture.audit.has("LOGIN_PREMIUM"), "and so is the completed login");
        assertTrue(fixture.audit.actions().indexOf("PREMIUM_CREATED")
                        < fixture.audit.actions().indexOf("LOGIN_PREMIUM"),
                "provisioning precedes the success audit");
    }

    @Test
    void aSessionJoinAuthenticatesAndAudits() {
        fixture.seedAccount("Olek", "verylongpw");
        fixture.sessions.sessionValid = true;
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Olek".getBytes()), "Olek");
        ConnectionHandle handle = fixture.connect(player);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, cracked());

        assertTrue(result.authenticated());
        assertEquals(Optional.of(AuthReason.SESSION), fixture.prompts.pendingLobbyGreeting(handle));
        assertTrue(fixture.audit.has("LOGIN_SESSION"));
        assertTrue(fixture.repository.writes.contains("recordSuccessfulLogin"));
    }

    @Test
    void anAdminBypassJoinAuthenticatesAndAudits() {
        FakeConnection staff = FakeConnection.of("Pola");
        ConnectionHandle handle = fixture.connect(staff);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, adminBypass());

        assertTrue(result.authenticated());
        assertTrue(handle.isAuthenticated());
        assertEquals(Optional.of(AuthReason.ADMIN_BYPASS), fixture.prompts.pendingLobbyGreeting(handle));
        assertTrue(fixture.audit.has("ADMIN_BYPASS_AUTH"));
    }

    @Test
    void anUnauthenticatedCrackedJoinCommitsTheStateWithoutAnySuccessAudit() {
        FakeConnection player = FakeConnection.of("Rafal");
        ConnectionHandle handle = fixture.connect(player);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, cracked());

        assertFalse(result.authenticated(), "this player still has to /register");
        assertTrue(handle.authState().isPresent(), "but their state is tracked");
        assertTrue(fixture.audit.entries.isEmpty(), "nothing succeeded, so nothing is audited");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
    }

    // ------------------------------------------- disconnect before the join commit point

    @Test
    void aPremiumJoinAbandonedBeforeTheCommitPointWritesNoSuccessAudit() {
        FakeConnection player = FakeConnection.of("Sara");
        ConnectionHandle handle = fixture.connect(player);
        fixture.disconnect(player);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, premium());

        assertFalse(result.denied());
        assertFalse(result.authenticated(), "an abandoned join is not an authenticated join");
        assertFalse(handle.isAuthenticated(), "no auth state may be attached");
        assertFalse(fixture.audit.has("LOGIN_PREMIUM"), "no success audit for a join that never completed");
        assertFalse(fixture.repository.writes.contains("recordSuccessfulLogin"),
                "no success bookkeeping either");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty(), "no greeting queued");
        // The provisioning that had to happen first is deliberately kept - see AuthFlow docs.
        assertTrue(fixture.backing.findByUuid(handle.uuid()).isPresent(),
                "the premium account row is provisioning, not a login, and is intentionally kept");
        assertTrue(fixture.audit.has("PREMIUM_CREATED"),
                "and its provisioning audit is kept with it, distinct from a success audit");
    }

    @Test
    void aSessionJoinAbandonedBeforeTheCommitPointWritesNoSuccessAudit() {
        fixture.seedAccount("Tomek", "verylongpw");
        fixture.sessions.sessionValid = true;
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Tomek".getBytes()), "Tomek");
        ConnectionHandle handle = fixture.connect(player);
        fixture.disconnect(player);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, cracked());

        assertFalse(result.authenticated());
        assertFalse(handle.isAuthenticated());
        assertFalse(fixture.audit.has("LOGIN_SESSION"), "no LOGIN_SESSION for an abandoned join");
        assertFalse(fixture.repository.writes.contains("recordSuccessfulLogin"));
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
    }

    @Test
    void anAdminBypassJoinAbandonedBeforeTheCommitPointWritesNoSuccessAudit() {
        FakeConnection staff = FakeConnection.of("Ula");
        ConnectionHandle handle = fixture.connect(staff);
        fixture.disconnect(staff);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, adminBypass());

        assertFalse(result.authenticated());
        assertFalse(handle.isAuthenticated());
        assertFalse(fixture.audit.has("ADMIN_BYPASS_AUTH"), "no ADMIN_BYPASS_AUTH for an abandoned join");
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
    }

    /**
     * The interesting window: the connection dies while the join is inside a database call, i.e.
     * after the decision has started but before the commit point is reached.
     */
    @Test
    void aPremiumJoinInterruptedMidLookupWritesNoSuccessAudit() throws InterruptedException {
        FakeConnection player = FakeConnection.of("Wera");
        ConnectionHandle handle = fixture.connect(player);
        // Provisioning is the first write a brand-new premium name performs; pause there.
        CountDownLatch atCreate = fixture.repository.pauseAt("create");

        AtomicReference<AuthFlow.JoinResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(fixture.flow.resolveJoin(handle, premium())), "join");
        worker.start();
        await(atCreate);

        fixture.disconnect(player);
        fixture.repository.resume("create");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(result.get().authenticated());
        assertFalse(handle.isAuthenticated(), "the connection ended before the commit point");
        assertFalse(fixture.audit.has("LOGIN_PREMIUM"));
        assertFalse(fixture.repository.writes.contains("recordSuccessfulLogin"));
        assertTrue(fixture.prompts.pendingLobbyGreeting(handle).isEmpty());
        assertEquals(0, fixture.registry.size());
    }

    /** A reconnect that supersedes the joining connection must not inherit its join. */
    @Test
    void aSupersededJoinDoesNotAuthenticateTheReplacement() throws InterruptedException {
        UUID uuid = UUID.nameUUIDFromBytes("u:Zenon".getBytes());
        FakeConnection first = new FakeConnection(uuid, "Zenon");
        ConnectionHandle oldHandle = fixture.connect(first);
        CountDownLatch atCreate = fixture.repository.pauseAt("create");

        Thread worker = new Thread(() -> fixture.flow.resolveJoin(oldHandle, premium()), "join");
        worker.start();
        await(atCreate);

        FakeConnection second = new FakeConnection(uuid, "Zenon");
        ConnectionHandle newHandle = fixture.connect(second);

        fixture.repository.resume("create");
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(newHandle.isAuthenticated(),
                "the replacement connection must run its own join, not inherit the old one's");
        assertTrue(fixture.prompts.pendingLobbyGreeting(newHandle).isEmpty());
        assertFalse(fixture.audit.has("LOGIN_PREMIUM"));
    }

    // ------------------------------------------------------------------ denials

    @Test
    void aCrackedNameCollidingWithAPremiumRowIsDeniedWithoutAuthenticating() {
        FakeConnection premiumOwner = FakeConnection.of("Kasia");
        fixture.flow.resolveJoin(fixture.connect(premiumOwner), premium());
        fixture.reset();

        FakeConnection cracked = new FakeConnection(UUID.randomUUID(), "Kasia");
        ConnectionHandle handle = fixture.connect(cracked);
        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, cracked());

        assertTrue(result.denied());
        assertEquals(Optional.of("disconnect.premium-name-required"), result.denyMessageKey());
        assertFalse(handle.isAuthenticated());
        assertTrue(fixture.audit.has("CRACKED_REJECT_ON_PREMIUM_NAME"));
        assertFalse(fixture.audit.has("LOGIN_SESSION"));
    }

    @Test
    void aPremiumNameBlockedByACrackedRowIsDeniedWithoutAuthenticating() {
        fixture.seedAccount("Lucja", "verylongpw");
        FakeConnection premiumOwner = new FakeConnection(UUID.randomUUID(), "Lucja");
        ConnectionHandle handle = fixture.connect(premiumOwner);

        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, premium());

        assertTrue(result.denied());
        assertEquals(Optional.of("disconnect.cracked-name-collision"), result.denyMessageKey());
        assertFalse(handle.isAuthenticated());
        assertFalse(fixture.audit.has("LOGIN_PREMIUM"));
    }

    @Test
    void aLostPremiumMigrationRaceIsDeniedWithoutAuthenticating() {
        Account pending = fixture.backing.create(new Account(
                0L, "marek", "Marek", AccountType.PENDING_MIGRATION,
                UUID.nameUUIDFromBytes("offline:Marek".getBytes()), null, "old-hash",
                1L, 1L, "ip-old", 0, null));
        assertEquals(AccountType.PENDING_MIGRATION, pending.accountType());
        // Make the atomic promotion report "somebody else got there first".
        fixture.repository.failAt("promotePendingMigrationToPremium",
                new IllegalStateException("forced race for the test"));

        FakeConnection player = new FakeConnection(UUID.randomUUID(), "Marek");
        ConnectionHandle handle = fixture.connect(player);
        AuthFlow.JoinResult result = fixture.flow.resolveJoin(handle, premium());

        assertTrue(result.denied());
        assertEquals(Optional.of("disconnect.account-state-error"), result.denyMessageKey());
        assertFalse(handle.isAuthenticated());
        assertTrue(fixture.audit.has("PREMIUM_MIGRATION_FAILED"));
        assertFalse(fixture.audit.has("LOGIN_PREMIUM"));
    }

    // ------------------------------------------------------------------ ordering sweep

    /**
     * Across every path, the rule is the same: a success audit exists if and only if the connection
     * was committed.
     */
    @Test
    void successAuditsExistIfAndOnlyIfTheJoinCommitted() {
        List<String> successActions = List.of("LOGIN_PREMIUM", "LOGIN_SESSION", "ADMIN_BYPASS_AUTH");

        for (boolean disconnectFirst : List.of(false, true)) {
            for (String path : List.of("premium", "session", "bypass")) {
                AuthFlowFixture f = new AuthFlowFixture();
                String username = path + (disconnectFirst ? "Gone" : "Live");
                AuthFlow.JoinRequest request;
                if ("session".equals(path)) {
                    f.seedAccount(username, "verylongpw");
                    f.sessions.sessionValid = true;
                    request = new AuthFlow.JoinRequest(false, false, "ip-hash");
                } else if ("premium".equals(path)) {
                    request = new AuthFlow.JoinRequest(true, false, "ip-hash");
                } else {
                    request = new AuthFlow.JoinRequest(false, true, "ip-hash");
                }
                FakeConnection player = new FakeConnection(
                        UUID.nameUUIDFromBytes(("u:" + username).getBytes()), username);
                ConnectionHandle handle = f.connect(player);
                if (disconnectFirst) {
                    f.disconnect(player);
                }

                f.flow.resolveJoin(handle, request);

                boolean committed = handle.isAuthenticated();
                boolean audited = successActions.stream().anyMatch(f.audit::has);
                assertEquals(committed, audited,
                        path + (disconnectFirst ? " (disconnected)" : " (live)")
                                + ": success audit must track the commit exactly");
                assertEquals(committed, f.prompts.pendingLobbyGreeting(handle).isPresent(),
                        path + ": the greeting must track the commit exactly");
            }
        }
    }
}
