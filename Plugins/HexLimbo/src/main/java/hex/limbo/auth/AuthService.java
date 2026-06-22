package hex.limbo.auth;

import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level authentication façade. Combines repository, password hashing, rate limiting, lockout,
 * and per-IP account caps. State for in-flight connections lives in {@link #states}.
 *
 * <p>Security tuning values (min password length, lockout, max-accounts-per-ip) are read fresh
 * from {@link RuntimeContext} on every call so {@code /hexlimbo reload} picks them up. Rate-limit
 * windows are not hot-reloaded; they require a proxy restart and the limitation is documented.
 */
public final class AuthService {

    public enum LoginOutcome {
        SUCCESS,
        WRONG_PASSWORD,
        ACCOUNT_LOCKED,
        ACCOUNT_NOT_FOUND,
        RATE_LIMITED
    }

    public enum RegisterOutcome {
        SUCCESS,
        PASSWORD_MISMATCH,
        PASSWORD_TOO_SHORT,
        ALREADY_REGISTERED,
        TOO_MANY_ACCOUNTS_FOR_IP,
        RATE_LIMITED,
        PREMIUM_NAME_PROTECTED
    }

    public enum LogoutOutcome {
        SUCCESS,
        NO_STATE,
        PREMIUM_NOT_SUPPORTED
    }

    private final AccountRepository repository;
    private final PasswordHasher passwordHasher;
    private final RateLimiter rateLimiter;
    private final RuntimeContext runtimeContext;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, AuthState> states = new ConcurrentHashMap<>();

    public AuthService(
            AccountRepository repository,
            PasswordHasher passwordHasher,
            RateLimiter rateLimiter,
            RuntimeContext runtimeContext,
            Logger logger
    ) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.rateLimiter = rateLimiter;
        this.runtimeContext = runtimeContext;
        this.logger = logger;
    }

    private PluginConfig.Security security() {
        return runtimeContext.config().security();
    }

    public void trackConnection(AuthState state) {
        states.put(state.uuid(), state);
    }

    public void removeConnection(UUID uuid) {
        states.remove(uuid);
    }

    public Optional<AuthState> stateOf(UUID uuid) {
        return Optional.ofNullable(states.get(uuid));
    }

    public boolean isAuthenticated(UUID uuid) {
        AuthState state = states.get(uuid);
        return state != null && state.isAuthenticated();
    }

    public LoginOutcome attemptLogin(UUID uuid, String password) {
        AuthState state = states.get(uuid);
        if (state == null) {
            return LoginOutcome.ACCOUNT_NOT_FOUND;
        }
        if (!rateLimiter.tryAcquire("login:" + state.username().toLowerCase(Locale.ROOT))) {
            return LoginOutcome.RATE_LIMITED;
        }
        Optional<Account> opt = repository.findByUsername(state.username());
        if (opt.isEmpty()) {
            return LoginOutcome.ACCOUNT_NOT_FOUND;
        }
        Account account = opt.get();
        long now = System.currentTimeMillis();
        if (account.isLockedAt(now)) {
            return LoginOutcome.ACCOUNT_LOCKED;
        }
        if (!passwordHasher.verify(password, account.passwordHash())) {
            int newCount = account.failedAttempts() + 1;
            Long lockedUntil = null;
            if (newCount >= security().maxFailedAttempts()) {
                lockedUntil = now + (security().lockoutSeconds() * 1_000L);
            }
            repository.updateFailedAttempts(account.id(), newCount, lockedUntil);
            return LoginOutcome.WRONG_PASSWORD;
        }
        repository.recordSuccessfulLogin(account.id(), now, state.ipHash(), state.username());
        state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);
        return LoginOutcome.SUCCESS;
    }

    public void markPremiumAuthenticated(UUID uuid) {
        AuthState state = states.get(uuid);
        if (state != null) {
            state.setStage(AuthState.Stage.AUTHENTICATED_PREMIUM);
        }
    }

    public RegisterOutcome attemptRegister(
            UUID uuid,
            String password,
            String passwordConfirm,
            boolean nameIsPremium
    ) {
        AuthState state = states.get(uuid);
        if (state == null) {
            return RegisterOutcome.ALREADY_REGISTERED;
        }
        if (nameIsPremium) {
            return RegisterOutcome.PREMIUM_NAME_PROTECTED;
        }
        if (!rateLimiter.tryAcquire("register:" + state.username().toLowerCase(Locale.ROOT))) {
            return RegisterOutcome.RATE_LIMITED;
        }
        if (password == null || passwordConfirm == null || !password.equals(passwordConfirm)) {
            return RegisterOutcome.PASSWORD_MISMATCH;
        }
        if (password.length() < security().minPasswordLength()) {
            return RegisterOutcome.PASSWORD_TOO_SHORT;
        }
        if (repository.findByUsername(state.username()).isPresent()) {
            return RegisterOutcome.ALREADY_REGISTERED;
        }
        if (state.ipHash() != null && repository.countByIp(state.ipHash()) >= security().maxAccountsPerIp()) {
            return RegisterOutcome.TOO_MANY_ACCOUNTS_FOR_IP;
        }
        long now = System.currentTimeMillis();
        Account account = new Account(
                0L,
                state.username().toLowerCase(Locale.ROOT),
                state.username(),
                AccountType.CRACKED,
                state.uuid(),
                null,
                passwordHasher.hash(password),
                now,
                now,
                state.ipHash(),
                0,
                null
        );
        repository.create(account);
        state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);
        return RegisterOutcome.SUCCESS;
    }

    public boolean changePassword(UUID uuid, String oldPassword, String newPassword) {
        AuthState state = states.get(uuid);
        if (state == null) {
            return false;
        }
        Optional<Account> opt = repository.findByUsername(state.username());
        if (opt.isEmpty()) {
            return false;
        }
        Account account = opt.get();
        if (!passwordHasher.verify(oldPassword, account.passwordHash())) {
            return false;
        }
        if (newPassword == null || newPassword.length() < security().minPasswordLength()) {
            return false;
        }
        repository.updatePasswordHash(account.id(), passwordHasher.hash(newPassword));
        return true;
    }

    /**
     * Demotes the player's auth stage to AWAITING_LOGIN. Premium accounts are intentionally not
     * logged out because they have no password and could not log back in without reconnecting –
     * callers should branch on {@link LogoutOutcome#PREMIUM_NOT_SUPPORTED}.
     */
    public LogoutOutcome logout(UUID uuid) {
        AuthState state = states.get(uuid);
        if (state == null) {
            return LogoutOutcome.NO_STATE;
        }
        if (state.stage() == AuthState.Stage.AUTHENTICATED_PREMIUM) {
            return LogoutOutcome.PREMIUM_NOT_SUPPORTED;
        }
        state.setStage(AuthState.Stage.AWAITING_LOGIN);
        return LogoutOutcome.SUCCESS;
    }

    public AccountRepository repository() {
        return repository;
    }

    public PasswordHasher passwordHasher() {
        return passwordHasher;
    }
}
