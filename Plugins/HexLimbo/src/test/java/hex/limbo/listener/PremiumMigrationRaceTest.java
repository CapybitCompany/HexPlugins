package hex.limbo.listener;

import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the decision the premium-migration branch of {@code LoginListener.handlePremiumLogin}
 * implements when the repository tells us the atomic promotion failed.
 *
 * <p>We don't go through a real Velocity {@code LoginEvent} – it's a sealed class with many
 * dependencies – but we exercise the same control flow against a real {@link AuthService} and a
 * {@link FakeRepo} that returns {@code false} (race lost) or throws (DB error). The contract
 * tested is: a denied login leaves no authenticated {@link AuthState} behind.
 */
class PremiumMigrationRaceTest {

    /** Delegate-based fake so we can intercept the promote method without touching the rest. */
    private static final class FakeRepo implements AccountRepository {
        private final InMemoryAccountRepository delegate = new InMemoryAccountRepository();
        boolean returnValue = true;
        boolean throwOnPromote = false;
        int callCount = 0;

        @Override public void initializeSchema() { delegate.initializeSchema(); }
        @Override public Optional<Account> findByUsername(String u) { return delegate.findByUsername(u); }
        @Override public Optional<Account> findByUuid(UUID u) { return delegate.findByUuid(u); }
        @Override public Account create(Account c) { return delegate.create(c); }
        @Override public void updatePasswordHash(long id, String h) { delegate.updatePasswordHash(id, h); }
        @Override public void updateFailedAttempts(long id, int n, Long u) { delegate.updateFailedAttempts(id, n, u); }
        @Override public void recordSuccessfulLogin(long id, long n, String i, String u) { delegate.recordSuccessfulLogin(id, n, i, u); }
        @Override public void updateAccountType(long id, AccountType t) { delegate.updateAccountType(id, t); }
        @Override public void updatePremiumUuid(long id, UUID u) { delegate.updatePremiumUuid(id, u); }
        @Override public void updateUuid(long id, UUID u) { delegate.updateUuid(id, u); }
        @Override public int countByIp(String h) { return delegate.countByIp(h); }
        @Override public void delete(long id) { delegate.delete(id); }
        @Override public void close() { delegate.close(); }

        @Override
        public synchronized boolean promotePendingMigrationToPremium(
                long id, UUID realUuid, long nowMillis, String ipHash, String lastUsername) {
            callCount++;
            if (throwOnPromote) {
                throw new IllegalStateException("forced failure for test");
            }
            return returnValue;
        }
    }

    private record PremiumLoginDecision(boolean authenticated, boolean denied, String reason) {}

    /**
     * Mirrors the listener's decision logic: returns whether AuthService gained a tracked state
     * and whether the listener would have denied the LoginEvent.
     */
    private PremiumLoginDecision simulatePremiumLogin(
            AccountRepository repository,
            AuthService authService,
            Account pendingAccount,
            UUID realUuid,
            String ipHash,
            String name
    ) {
        try {
            boolean promoted = repository.promotePendingMigrationToPremium(
                    pendingAccount.id(), realUuid, System.currentTimeMillis(), ipHash, name);
            if (!promoted) {
                return new PremiumLoginDecision(false, true, "race-lost");
            }
        } catch (RuntimeException ex) {
            return new PremiumLoginDecision(false, true, "exception:" + ex.getMessage());
        }
        AuthState state = new AuthState(realUuid, name, ipHash, AuthState.Stage.AUTHENTICATED_PREMIUM, AccountType.PREMIUM);
        authService.trackConnection(state);
        return new PremiumLoginDecision(true, false, "migrated");
    }

    private Account makePending(AccountRepository repo, String name) {
        Account candidate = new Account(
                0L,
                name.toLowerCase(),
                name,
                AccountType.PENDING_MIGRATION,
                UUID.nameUUIDFromBytes(("offline:" + name).getBytes()),
                null,
                "old-hash",
                1L, 1L, "ip-old", 0, null
        );
        return repo.create(candidate);
    }

    private AuthService authService(AccountRepository repo) {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        return new AuthService(
                repo,
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
                LoggerFactory.getLogger(PremiumMigrationRaceTest.class)
        );
    }

    @Test
    void successfulPromotionLeadsToAuthenticatedState() {
        FakeRepo repo = new FakeRepo();
        repo.returnValue = true;
        AuthService service = authService(repo);
        Account pending = makePending(repo, "Alice");
        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-aaaaaaaaaaaa");

        PremiumLoginDecision decision = simulatePremiumLogin(repo, service, pending, realUuid, "ip-new", "Alice");

        assertTrue(decision.authenticated());
        assertFalse(decision.denied());
        Optional<AuthState> tracked = service.stateOf(realUuid);
        assertTrue(tracked.isPresent());
        assertEquals(AuthState.Stage.AUTHENTICATED_PREMIUM, tracked.get().stage());
        assertEquals(1, repo.callCount);
    }

    @Test
    void raceLostPromotionDeniesAndLeavesNoState() {
        FakeRepo repo = new FakeRepo();
        repo.returnValue = false;
        AuthService service = authService(repo);
        Account pending = makePending(repo, "Bob");
        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-bbbbbbbbbbbb");

        PremiumLoginDecision decision = simulatePremiumLogin(repo, service, pending, realUuid, "ip-new", "Bob");

        assertFalse(decision.authenticated());
        assertTrue(decision.denied());
        assertEquals("race-lost", decision.reason());
        assertTrue(service.stateOf(realUuid).isEmpty(),
                "AuthService must NOT track the player when the migration could not be proven.");
    }

    @Test
    void throwingPromotionDeniesAndLeavesNoState() {
        FakeRepo repo = new FakeRepo();
        repo.throwOnPromote = true;
        AuthService service = authService(repo);
        Account pending = makePending(repo, "Carol");
        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-cccccccccccc");

        PremiumLoginDecision decision = simulatePremiumLogin(repo, service, pending, realUuid, "ip-new", "Carol");

        assertFalse(decision.authenticated());
        assertTrue(decision.denied());
        assertTrue(decision.reason().startsWith("exception:"));
        assertTrue(service.stateOf(realUuid).isEmpty());
    }
}
