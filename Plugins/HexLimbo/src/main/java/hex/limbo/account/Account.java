package hex.limbo.account;

import java.util.Objects;
import java.util.UUID;

/**
 * Mutable account record returned from the repository. Mirrors the {@code hex_limbo_accounts} row.
 */
public final class Account {

    private long id;
    private String usernameLower;
    private String lastUsername;
    private AccountType accountType;
    private UUID uuid;
    private UUID premiumUuid;
    private String passwordHash;
    private long registeredAt;
    private Long lastLoginAt;
    private String lastIpHash;
    private int failedAttempts;
    private Long lockedUntil;

    public Account(
            long id,
            String usernameLower,
            String lastUsername,
            AccountType accountType,
            UUID uuid,
            UUID premiumUuid,
            String passwordHash,
            long registeredAt,
            Long lastLoginAt,
            String lastIpHash,
            int failedAttempts,
            Long lockedUntil
    ) {
        this.id = id;
        this.usernameLower = Objects.requireNonNull(usernameLower, "usernameLower");
        this.lastUsername = Objects.requireNonNull(lastUsername, "lastUsername");
        this.accountType = Objects.requireNonNull(accountType, "accountType");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.premiumUuid = premiumUuid;
        this.passwordHash = passwordHash;
        this.registeredAt = registeredAt;
        this.lastLoginAt = lastLoginAt;
        this.lastIpHash = lastIpHash;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
    }

    public long id() { return id; }
    public void setId(long id) { this.id = id; }
    public String usernameLower() { return usernameLower; }
    public String lastUsername() { return lastUsername; }
    public void setLastUsername(String lastUsername) { this.lastUsername = lastUsername; }
    public AccountType accountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public UUID uuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }
    public UUID premiumUuid() { return premiumUuid; }
    public void setPremiumUuid(UUID premiumUuid) { this.premiumUuid = premiumUuid; }
    public String passwordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public long registeredAt() { return registeredAt; }
    public Long lastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Long lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String lastIpHash() { return lastIpHash; }
    public void setLastIpHash(String lastIpHash) { this.lastIpHash = lastIpHash; }
    public int failedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public Long lockedUntil() { return lockedUntil; }
    public void setLockedUntil(Long lockedUntil) { this.lockedUntil = lockedUntil; }

    public boolean isLockedAt(long nowMillis) {
        return lockedUntil != null && lockedUntil > nowMillis;
    }
}
