package hex.limbo.account;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory repository used by tests and as a fallback when MySQL is misconfigured.
 * Production runs use {@link SqlAccountRepository}.
 */
public final class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<String, Account> byUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Account> byUuid = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1L);

    @Override
    public void initializeSchema() {
        // No-op for in-memory.
    }

    @Override
    public Optional<Account> findByUsername(String usernameLower) {
        return Optional.ofNullable(byUsername.get(usernameLower.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<Account> findByUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @Override
    public synchronized Account create(Account candidate) {
        if (byUsername.containsKey(candidate.usernameLower())) {
            throw new IllegalStateException("Duplicate username: " + candidate.usernameLower());
        }
        if (byUuid.containsKey(candidate.uuid())) {
            throw new IllegalStateException("Duplicate uuid: " + candidate.uuid());
        }
        candidate.setId(sequence.getAndIncrement());
        byUsername.put(candidate.usernameLower(), candidate);
        byUuid.put(candidate.uuid(), candidate);
        return candidate;
    }

    @Override
    public void updatePasswordHash(long id, String passwordHash) {
        mutate(id, account -> {
            account.setPasswordHash(passwordHash);
            account.setFailedAttempts(0);
            account.setLockedUntil(null);
        });
    }

    @Override
    public void updateFailedAttempts(long id, int failedAttempts, Long lockedUntilMillis) {
        mutate(id, account -> {
            account.setFailedAttempts(failedAttempts);
            account.setLockedUntil(lockedUntilMillis);
        });
    }

    @Override
    public void recordSuccessfulLogin(long id, long nowMillis, String ipHash, String lastUsername) {
        mutate(id, account -> {
            account.setLastLoginAt(nowMillis);
            account.setLastIpHash(ipHash);
            account.setLastUsername(lastUsername);
            account.setFailedAttempts(0);
            account.setLockedUntil(null);
        });
    }

    @Override
    public void updateAccountType(long id, AccountType type) {
        mutate(id, account -> account.setAccountType(type));
    }

    @Override
    public void updatePremiumUuid(long id, UUID premiumUuid) {
        mutate(id, account -> account.setPremiumUuid(premiumUuid));
    }

    @Override
    public synchronized void updateUuid(long id, UUID uuid) {
        Account account = findById(id);
        if (account == null) {
            return;
        }
        byUuid.remove(account.uuid());
        account.setUuid(uuid);
        byUuid.put(uuid, account);
    }

    @Override
    public synchronized boolean promotePendingMigrationToPremium(
            long id,
            UUID realUuid,
            long nowMillis,
            String ipHash,
            String lastUsername
    ) {
        Account account = findById(id);
        if (account == null || account.accountType() != AccountType.PENDING_MIGRATION) {
            return false;
        }
        UUID oldUuid = account.uuid();
        // Mutate all fields together under the lock so no caller can see a half-migrated row.
        byUuid.remove(oldUuid);
        account.setUuid(realUuid);
        account.setPremiumUuid(realUuid);
        account.setAccountType(AccountType.PREMIUM);
        account.setLastLoginAt(nowMillis);
        account.setLastIpHash(ipHash);
        account.setLastUsername(lastUsername);
        account.setFailedAttempts(0);
        account.setLockedUntil(null);
        byUuid.put(realUuid, account);
        return true;
    }

    @Override
    public int countByIp(String ipHash) {
        if (ipHash == null) {
            return 0;
        }
        int count = 0;
        for (Account account : byUsername.values()) {
            if (ipHash.equals(account.lastIpHash())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized void delete(long id) {
        Account account = findById(id);
        if (account == null) {
            return;
        }
        byUsername.remove(account.usernameLower());
        byUuid.remove(account.uuid());
    }

    @Override
    public void close() {
        byUsername.clear();
        byUuid.clear();
    }

    private synchronized void mutate(long id, java.util.function.Consumer<Account> mutator) {
        Account account = findById(id);
        if (account != null) {
            mutator.accept(account);
        }
    }

    private Account findById(long id) {
        for (Account account : byUsername.values()) {
            if (account.id() == id) {
                return account;
            }
        }
        return null;
    }
}
