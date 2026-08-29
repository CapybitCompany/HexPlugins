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

/**
 * Top-level authentication façade. Combines repository, password hashing, rate limiting, lockout,
 * and per-IP account caps.
 *
 * <p>Per-connection state is not held here: it hangs off the {@link ConnectionHandle} owned by
 * {@link ConnectionRegistry}. Every mutating entry point therefore takes a handle rather than a
 * UUID, and establishes that the handle is still the current connection by acquiring the UUID's
 * commit slot - which is also what orders it against every other operation on the same account.
 *
 * <h2>Commit point</h2>
 * A currency check cannot make a read-decide-write sequence safe on its own: a disconnect, a
 * reconnect or a concurrent {@code /logout} can land between the check and the database call. Every
 * operation here therefore runs inside the UUID's commit slot, taken as a
 * {@link ConnectionRegistry.CommitLease} <b>before the first repository read</b>, and acquiring
 * that lease is the linearization point:
 *
 * <ul>
 *     <li>Lease refused - the connection ended or was superseded. Nothing is read and nothing is
 *     written; the method returns {@code CONNECTION_STALE}. There is no ordering that produces
 *     "{@code CONNECTION_STALE} plus a created account".</li>
 *     <li>Lease acquired - the operation owns the account for its whole duration. It sees every
 *     earlier commit for the same UUID (it waited for them) and no later one can overtake it, so
 *     two connections of one account can neither create the same row twice nor decide on a picture
 *     that is already out of date.</li>
 *     <li>A disconnect that lands while the lease is held does not undo the commit: the write and
 *     the auth-state change both go through and the method reports success. Suppressing what such a
 *     player must not receive (greeting, chat confirmation, routing) is the caller's job and is
 *     keyed off the connection, not off the outcome.</li>
 *     <li>The write throws - the lease is released without a state change, so a database failure can
 *     never leave an authenticated player without the row backing them.</li>
 * </ul>
 *
 * <p>Each entry point comes in two forms. The public one runs the operation as its own ordered
 * section and is what direct callers (staff commands, tests) use. The package-private overload
 * takes a lease the caller already holds, which is how {@link AuthFlow} keeps its own bookkeeping -
 * session row, audit entry, queued greeting - inside the same section as the write it belongs to.
 * The two must never be nested: {@link ConnectionRegistry#enterCommitOrder} rejects that outright.
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
        RATE_LIMITED,
        /** The player disconnected (or reconnected) while the password was being verified. */
        CONNECTION_STALE
    }

    public enum RegisterOutcome {
        SUCCESS,
        PASSWORD_MISMATCH,
        PASSWORD_TOO_SHORT,
        ALREADY_REGISTERED,
        TOO_MANY_ACCOUNTS_FOR_IP,
        RATE_LIMITED,
        PREMIUM_NAME_PROTECTED,
        /** The player disconnected (or reconnected) while the registration was being processed. */
        CONNECTION_STALE
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
    private final ConnectionRegistry connections;
    private final Logger logger;

    public AuthService(
            AccountRepository repository,
            PasswordHasher passwordHasher,
            RateLimiter rateLimiter,
            RuntimeContext runtimeContext,
            ConnectionRegistry connections,
            Logger logger
    ) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.rateLimiter = rateLimiter;
        this.runtimeContext = runtimeContext;
        this.connections = connections;
        this.logger = logger;
    }

    /** The registry every caller must go through to obtain a handle. */
    public ConnectionRegistry connections() {
        return connections;
    }

    private PluginConfig.Security security() {
        return runtimeContext.config().security();
    }

    /**
     * Attaches the freshly computed auth state to the connection it was computed for. Returns false
     * when that connection has already ended or been superseded - the state is then discarded,
     * which is correct because nothing can reach it any more.
     */
    public boolean trackConnection(ConnectionHandle handle, AuthState state) {
        return connections.attachAuthState(handle, state);
    }

    /** Auth state of whichever connection currently owns the UUID. */
    public Optional<AuthState> stateOf(UUID uuid) {
        return connections.current(uuid).flatMap(ConnectionHandle::authState);
    }

    /** Auth state of {@code handle}, but only while that handle is still the current connection. */
    public Optional<AuthState> stateOf(ConnectionHandle handle) {
        return connections.isCurrent(handle) ? handle.authState() : Optional.empty();
    }

    /**
     * Whether <em>whichever</em> connection currently owns this UUID is authenticated.
     *
     * <p><b>Never use this to gate a player event.</b> It answers a question about an account, not
     * about a socket, so a superseded connection would inherit the auth state of the reconnect that
     * displaced it and be waved through. An event handler holds a concrete {@code Player} and must
     * ask {@link ConnectionRegistry#isAuthenticatedConnection}, which is fail-closed for exactly
     * that case. This overload is for staff diagnostics and account-level queries.
     */
    public boolean isAuthenticated(UUID uuid) {
        return connections.current(uuid).map(ConnectionHandle::isAuthenticated).orElse(false);
    }

    /**
     * Verifies a password for one specific connection, as its own ordered section.
     *
     * <p>The lease is taken before the account row is read, so the verification cannot be based on
     * a picture an earlier commit has already changed, and no later commit for the same account can
     * overtake it.
     */
    public LoginOutcome attemptLogin(ConnectionHandle handle, String password) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return LoginOutcome.CONNECTION_STALE;
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            return attemptLogin(handle, password, section);
        }
    }

    /**
     * Verifies a password inside a lease the caller already holds, so the caller's own success
     * bookkeeping stays in the same ordered section as the write.
     */
    LoginOutcome attemptLogin(ConnectionHandle handle, String password, ConnectionRegistry.CommitLease lease) {
        AuthState state = handle.authState().orElse(null);
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
        // The password was right. The lease was already won before the read above, so this login
        // definitively happened: there is no ordering in which the account row is updated and the
        // caller is then told the commit was discarded. A throwing write propagates without any
        // auth-state change, so an authenticated player never exists without the write behind them.
        repository.recordSuccessfulLogin(account.id(), now, state.ipHash(), state.username());
        lease.commit(() -> state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED));
        return LoginOutcome.SUCCESS;
    }

    /** Registers an account as its own ordered section. */
    public RegisterOutcome attemptRegister(
            ConnectionHandle handle,
            String password,
            String passwordConfirm,
            boolean nameIsPremium
    ) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return RegisterOutcome.CONNECTION_STALE;
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            return attemptRegister(handle, password, passwordConfirm, nameIsPremium, section);
        }
    }

    /**
     * Registers an account inside a lease the caller already holds. The "is this name taken"
     * lookup runs inside the section, which is what stops two connections of the same account from
     * both deciding the row is free and racing into a duplicate-key failure.
     */
    RegisterOutcome attemptRegister(
            ConnectionHandle handle,
            String password,
            String passwordConfirm,
            boolean nameIsPremium,
            ConnectionRegistry.CommitLease lease
    ) {
        AuthState state = handle.authState().orElse(null);
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
        lease.commit(() -> state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED));
        return RegisterOutcome.SUCCESS;
    }

    /** Rotates the password as its own ordered section. */
    public boolean changePassword(ConnectionHandle handle, String oldPassword, String newPassword) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return false;
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            return changePassword(handle, oldPassword, newPassword, section);
        }
    }

    /** Rotates the password inside a lease the caller already holds. */
    boolean changePassword(
            ConnectionHandle handle,
            String oldPassword,
            String newPassword,
            ConnectionRegistry.CommitLease lease
    ) {
        AuthState state = handle.authState().orElse(null);
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
        String newHash = passwordHasher.hash(newPassword);
        repository.updatePasswordHash(account.id(), newHash);
        // No auth-state change belongs to a password change; the lease only orders the write.
        lease.commit(() -> { });
        return true;
    }

    /**
     * Demotes the player's auth stage to AWAITING_LOGIN, as its own ordered section. Premium
     * accounts are intentionally not logged out because they have no password and could not log
     * back in without reconnecting – callers should branch on
     * {@link LogoutOutcome#PREMIUM_NOT_SUPPORTED}.
     *
     * <p>The section is what makes a logout beat a {@code /login} that is still committing: it
     * waits for that login and demotes afterwards, instead of being undone by it.
     */
    public LogoutOutcome logout(ConnectionHandle handle) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return LogoutOutcome.NO_STATE;
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            return logout(handle, section);
        }
    }

    /**
     * Demotes the player's auth stage inside a lease the caller already holds, so the caller's
     * session invalidation and prompt teardown share the section with the demotion.
     */
    LogoutOutcome logout(ConnectionHandle handle, ConnectionRegistry.CommitLease lease) {
        AuthState state = handle.authState().orElse(null);
        if (state == null) {
            return LogoutOutcome.NO_STATE;
        }
        if (state.stage() == AuthState.Stage.AUTHENTICATED_PREMIUM) {
            return LogoutOutcome.PREMIUM_NOT_SUPPORTED;
        }
        lease.commit(() -> state.setStage(AuthState.Stage.AWAITING_LOGIN));
        return LogoutOutcome.SUCCESS;
    }

    public AccountRepository repository() {
        return repository;
    }

    public PasswordHasher passwordHasher() {
        return passwordHasher;
    }
}
