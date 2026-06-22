package hex.limbo.auth;

import hex.limbo.account.AccountType;

import java.util.UUID;

/**
 * Per-connection auth status held while the player is online. Tracked separately from the DB row
 * because we need to react synchronously on the proxy thread when servers try to switch.
 */
public final class AuthState {

    public enum Stage {
        // No DB account exists yet: must /register
        UNREGISTERED,
        // DB account exists, player must /login
        AWAITING_LOGIN,
        // Verified premium player: auto-authenticated by Velocity online-mode
        AUTHENTICATED_PREMIUM,
        // Authenticated cracked player after /login or valid session
        AUTHENTICATED_CRACKED
    }

    private final UUID uuid;
    private final String username;
    private final String ipHash;
    private volatile Stage stage;
    private final AccountType accountType;
    private final long joinedAt = System.currentTimeMillis();

    public AuthState(UUID uuid, String username, String ipHash, Stage stage, AccountType accountType) {
        this.uuid = uuid;
        this.username = username;
        this.ipHash = ipHash;
        this.stage = stage;
        this.accountType = accountType;
    }

    public UUID uuid() { return uuid; }
    public String username() { return username; }
    public String ipHash() { return ipHash; }
    public Stage stage() { return stage; }
    public void setStage(Stage stage) { this.stage = stage; }
    public AccountType accountType() { return accountType; }
    public long joinedAt() { return joinedAt; }

    public boolean isAuthenticated() {
        return stage == Stage.AUTHENTICATED_PREMIUM || stage == Stage.AUTHENTICATED_CRACKED;
    }
}
