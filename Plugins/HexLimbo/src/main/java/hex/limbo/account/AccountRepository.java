package hex.limbo.account;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage abstraction over accounts. Implementations may be backed by JDBC or an in-memory map
 * (used by tests). All methods are synchronous; callers route off the proxy thread themselves.
 */
public interface AccountRepository {

    void initializeSchema();

    Optional<Account> findByUsername(String usernameLower);

    Optional<Account> findByUuid(UUID uuid);

    Account create(Account candidate);

    void updatePasswordHash(long id, String passwordHash);

    void updateFailedAttempts(long id, int failedAttempts, Long lockedUntilMillis);

    void recordSuccessfulLogin(long id, long nowMillis, String ipHash, String lastUsername);

    void updateAccountType(long id, AccountType type);

    void updatePremiumUuid(long id, UUID premiumUuid);

    void updateUuid(long id, UUID uuid);

    /**
     * Atomic PENDING_MIGRATION → PREMIUM upgrade. Sets {@code uuid}, {@code premium_uuid},
     * {@code account_type=PREMIUM}, {@code last_login_at}, {@code last_ip_hash}, and
     * {@code last_username} in a single statement so a crash between the individual updates can
     * never leave the row half-migrated.
     *
     * @return {@code true} iff exactly one row was promoted from PENDING_MIGRATION to PREMIUM;
     *         {@code false} when the row is gone or no longer PENDING_MIGRATION (e.g. a concurrent
     *         login already migrated it). Callers MUST treat {@code false} as a hard failure and
     *         not authenticate the player.
     * @throws IllegalStateException if more than one row matched – this is database corruption
     *         and we refuse to silently authenticate against an ambiguous identity.
     */
    boolean promotePendingMigrationToPremium(
            long id,
            UUID realUuid,
            long nowMillis,
            String ipHash,
            String lastUsername
    );

    int countByIp(String ipHash);

    void delete(long id);

    void close();
}
