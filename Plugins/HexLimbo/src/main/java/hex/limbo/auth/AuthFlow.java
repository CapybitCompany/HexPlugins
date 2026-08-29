package hex.limbo.auth;

import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.config.RuntimeContext;
import hex.limbo.premium.PremiumResolver;
import hex.limbo.prompt.AuthReason;
import hex.limbo.prompt.PromptService;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides what a completed authentication actually does.
 *
 * <p>Listeners and commands hold no ordering logic of their own any more: they translate a Velocity
 * event into a call here and then apply the returned {@link Result} (send a message, deny the join,
 * route the player). Everything that has to happen in a particular order relative to the connection
 * lifecycle - the commit point, the persistent writes, the audit entries, the queued greeting -
 * lives in this class, so a test that drives {@code AuthFlow} is exercising the same code the proxy
 * runs rather than a re-implementation of it.
 *
 * <h2>Ordering and commit points</h2>
 * Every method here runs as a single ordered section for the player's UUID, held from before the
 * first repository read until after the last piece of bookkeeping. <b>The linearization point is
 * the acquisition of that section</b> - {@link ConnectionRegistry#beginCommit} for the command
 * flows, {@link ConnectionRegistry#enterCommitOrder} for the join pipeline - and
 * {@link ConnectionRegistry.CommitLease} documents what it does and does not order.
 *
 * <p>Two consequences shape the code below:
 *
 * <ul>
 *     <li><b>Decisions and their writes are inseparable.</b> "Is this name registered", "is this
 *     account still CRACKED", "does this UUID have a row" are read inside the section, so a second
 *     connection for the same account cannot act on a picture the first one has already changed.
 *     It waits for the first commit and then re-reads.</li>
 *     <li><b>The whole tail belongs to the operation.</b> Session row, audit entry, prompt teardown
 *     and queued greeting are all inside the section, so a later {@code /logout} cannot be
 *     half-overtaken - it either runs entirely before the login or entirely after it, and the
 *     later one wins.</li>
 * </ul>
 *
 * <dl>
 *     <dt>{@link #login} / {@link #register} / {@link #changePassword} / {@link #requestPremiumMigration}</dt>
 *     <dd>The section is refused outright when the handle is already stale, so nothing at all is
 *     read or written. Once it is granted the operation counts as committed and its records -
 *     session row and success audit - are written even if the player vanishes immediately
 *     afterwards, because they describe something that really happened. Only what is addressed at
 *     the player (greeting, chat confirmation, routing) is suppressed, keyed off whether the
 *     connection is still live.</dd>
 *
 *     <dt>{@link #logout} / {@link #forceLogout}</dt>
 *     <dd>Same section, which is what orders a logout against a login that is still committing.
 *     The demotion, the session invalidation and the prompt teardown are one unit: a login that
 *     acquired the section earlier has already finished creating its session by the time the logout
 *     invalidates it.</dd>
 *
 *     <dt>{@link #resolveJoin}</dt>
 *     <dd>Enters the section without a currency check (its provisioning is deliberately
 *     unconditional, see below). {@link AuthService#trackConnection} is the commit point, followed
 *     by {@link PromptService#markAuthenticated} for the paths that authenticate immediately. Both
 *     return a boolean and <b>both are checked</b>: if either reports that the connection has ended
 *     or been superseded, the flow stops before writing any success bookkeeping, so no
 *     {@code LOGIN_PREMIUM}, {@code LOGIN_SESSION} or {@code ADMIN_BYPASS_AUTH} entry can describe a
 *     join that never completed.</dd>
 * </dl>
 *
 * <h2>Provisioning is deliberately not part of the commit</h2>
 * A premium join may have to create or migrate the account row <em>before</em> anyone can decide
 * whether the join is allowed - the row is what the decision is based on. Those writes therefore
 * happen ahead of the commit point and are intentionally kept even when the join is then aborted:
 * they record an identity fact proven by Mojang (this UUID owns this name), not a successful login,
 * and re-deriving them on every attempt would be both wasteful and racy. They get their own audit
 * actions ({@code PREMIUM_CREATED}, {@code PREMIUM_MIGRATION}) which are explicitly
 * <em>provisioning</em> entries, never success entries. {@code recordSuccessfulLogin} and
 * {@code LOGIN_PREMIUM} are success bookkeeping and stay behind the commit point.
 */
public final class AuthFlow {

    /** Where the caller should send the player once the flow returns. */
    public enum Routing {
        /** Leave the player where they are. */
        NONE,
        /** Send them to the configured target server. */
        TARGET,
        /** Send them back to the limbo. */
        LIMBO,
        /** Disconnect them with {@link Result#messageKey()}. */
        DISCONNECT
    }

    /**
     * What the caller has to do. {@code messageKey} is empty when nothing should be shown - which
     * is how a flow reports "this connection is gone, stay silent".
     *
     * <p>{@code stamp} is the ticket the result needs before any of it may reach the player. It
     * names the operation that produced the result, and {@link FlowResultApplier} refuses to apply
     * a result whose operation has since been overtaken. Only a result with no effects at all -
     * {@link #silent()} - is allowed to travel without one.
     */
    public record Result(Optional<String> messageKey, List<Object> messageArgs, Routing routing,
                         Optional<ConnectionRegistry.OperationStamp> stamp) {

        public static Result silent() {
            return new Result(Optional.empty(), List.of(), Routing.NONE, Optional.empty());
        }

        public static Result message(String key, Object... args) {
            return new Result(Optional.of(key), List.of(args), Routing.NONE, Optional.empty());
        }

        public static Result messageAndRoute(String key, Routing routing, Object... args) {
            return new Result(Optional.of(key), List.of(args), routing, Optional.empty());
        }

        /** A pure routing decision, with nothing to say. */
        public static Result route(Routing routing) {
            return new Result(Optional.empty(), List.of(), routing, Optional.empty());
        }

        /**
         * Binds this result to the ordered operation that produced it. Every result that has any
         * player-facing effect goes through here before it leaves {@link AuthFlow}.
         */
        public Result at(ConnectionRegistry.OperationStamp stamp) {
            return new Result(messageKey, messageArgs, routing, Optional.of(stamp));
        }

        /** Whether there is anything at all to show the player or do to their connection. */
        public boolean hasEffects() {
            return messageKey.isPresent() || routing != Routing.NONE;
        }

        public Object[] args() {
            return messageArgs.toArray();
        }
    }

    /**
     * What {@code /hexlimbo forcelogout} needs back: the outcome it reports to the staff member, and
     * the ticket its player-facing effect has to be applied under.
     */
    public record ForcedLogout(AuthService.LogoutOutcome outcome,
                               Optional<ConnectionRegistry.OperationStamp> stamp) {

        /**
         * What the forced logout does to the player, bound to its own ordered operation so it is
         * dropped if a later operation has overtaken it.
         *
         * <p>A premium account has no password and could never re-authenticate in the limbo, so it
         * is kicked with a clear reason instead of being routed there. Everyone else simply goes
         * back to the limbo, where the prompt greets them again.
         */
        public Result playerEffect() {
            Result effect = switch (outcome) {
                case PREMIUM_NOT_SUPPORTED ->
                        Result.messageAndRoute("admin.forcelogout.kick-premium", Routing.DISCONNECT);
                case SUCCESS -> Result.route(Routing.LIMBO);
                case NO_STATE -> Result.silent();
            };
            return stamp.map(effect::at).orElseGet(Result::silent);
        }

        /**
         * The message key describing to the staff member what actually became of the player.
         *
         * <p>Three things are reported apart, because they really are three things: the demotion,
         * whether its visible effect was applied in order at all, and - when that effect was a
         * transfer - whether the player <em>arrived</em>. Only a confirmed arrival (or an
         * unambiguous "already there") earns "przekierowano do serwera poczekalni". A decision that
         * was merely handed to the router, or that was superseded, failed, or belonged to a socket
         * that vanished, says so instead: an operator who is told a player is in the limbo when they
         * are back on the target server has been actively misinformed.
         *
         * @param effect how {@link #playerEffect()} was applied
         * @param route  how the transfer it asked for finally ended, or {@code null} when nothing
         *               was routed - a kick, or no effect at all
         */
        public String staffMessageKey(ConnectionRegistry.ApplyOutcome effect,
                                      RouteCoordinator.RouteResult route) {
            return switch (effect) {
                case APPLIED -> appliedMessageKey(route);
                case OVERTAKEN -> "admin.forcelogout.overtaken";
                case STALE_CONNECTION -> "admin.forcelogout.connection-gone";
                case NO_EFFECT, UNSTAMPED -> "admin.forcelogout.no-state";
            };
        }

        private String appliedMessageKey(RouteCoordinator.RouteResult route) {
            if (outcome == AuthService.LogoutOutcome.PREMIUM_NOT_SUPPORTED) {
                // A kick, not a transfer: it takes effect the moment it is applied.
                return "admin.forcelogout.kicked-premium";
            }
            if (route == null) {
                // Applied but nothing to wait on. Nothing proves an arrival and nothing proves a
                // disconnect either, so neither is claimed.
                return "admin.forcelogout.route-failed-unknown";
            }
            return switch (route) {
                case REACHED, ALREADY_THERE -> "admin.forcelogout.sent-limbo";
                case SUPERSEDED -> "admin.forcelogout.overtaken";
                case CONNECTION_GONE -> "admin.forcelogout.connection-gone";
                // Each failure shape gets its own sentence, because they leave the player in three
                // different places. Claiming a closed connection that is still open sends staff
                // looking for a ghost; claiming an open one that was closed does the reverse; and
                // after a throwing disconnect neither is known, so neither is said.
                case FAILED_DISCONNECTED -> "admin.forcelogout.route-failed";
                case FAILED_CONNECTION_KEPT -> "admin.forcelogout.route-failed-connected";
                case FAILED_DISCONNECT_UNKNOWN -> "admin.forcelogout.route-failed-unknown";
            };
        }
    }

    /** Outcome of the join pipeline, translated by the listener into the {@code LoginEvent} result. */
    public record JoinResult(boolean denied, Optional<String> denyMessageKey, boolean authenticated) {

        static JoinResult allowed(boolean authenticated) {
            return new JoinResult(false, Optional.empty(), authenticated);
        }

        static JoinResult denied(String messageKey) {
            return new JoinResult(true, Optional.of(messageKey), false);
        }

        /** The connection ended mid-pipeline: nothing to allow, nothing to deny, nothing to say. */
        static JoinResult abandoned() {
            return new JoinResult(false, Optional.empty(), false);
        }
    }

    /** Everything the join pipeline needs that does not already live on the handle. */
    public record JoinRequest(boolean onlineMode, boolean adminBypass, String ipHash) {}

    /** Minimal audit façade so the flow can be tested without a database. */
    public interface AuditLog {
        void record(String action, String usernameLower, UUID uuid, String ipHash, String detail);
    }

    /** Minimal session façade so the flow can be tested without a database. */
    public interface Sessions {
        void createSession(long accountId, UUID uuid, String usernameLower, String ipHash);

        Optional<Long> findValidSessionExpiry(UUID uuid, String ipHash);

        void invalidate(UUID uuid);
    }

    private final AuthService authService;
    private final ConnectionRegistry connections;
    private final AccountRepository repository;
    private final Sessions sessions;
    private final AuditLog auditLog;
    private final PromptService promptService;
    private final PremiumResolver premiumResolver;
    private final RuntimeContext context;
    private final Logger logger;

    public AuthFlow(
            AuthService authService,
            AccountRepository repository,
            Sessions sessions,
            AuditLog auditLog,
            PromptService promptService,
            PremiumResolver premiumResolver,
            RuntimeContext context,
            Logger logger
    ) {
        this.authService = authService;
        this.connections = authService.connections();
        this.repository = repository;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.promptService = promptService;
        this.premiumResolver = premiumResolver;
        this.context = context;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ join pipeline

    /**
     * The body of {@code LoginEvent}: works out who this player is, provisions their account row if
     * a premium join requires it, and commits the connection.
     */
    public JoinResult resolveJoin(ConnectionHandle handle, JoinRequest request) {
        // enterCommitOrder, not beginCommit: the join must still provision a premium row for a
        // connection that has already gone (see "Provisioning" below), so it may not be skipped on
        // the strength of a stale handle. It does have to be ordered against every other commit for
        // the account, which is what the section provides - a reconnect reads the finished picture
        // its predecessor left behind rather than racing it.
        try (ConnectionRegistry.CommitLease lease = connections.enterCommitOrder(handle)) {
            if (request.adminBypass()) {
                return resolveAdminBypass(handle, request);
            }
            return request.onlineMode()
                    ? resolvePremiumJoin(handle, request)
                    : resolveCrackedJoin(handle, request);
        }
    }

    private JoinResult resolveAdminBypass(ConnectionHandle handle, JoinRequest request) {
        AuthState.Stage stage = request.onlineMode()
                ? AuthState.Stage.AUTHENTICATED_PREMIUM
                : AuthState.Stage.AUTHENTICATED_CRACKED;
        AccountType type = request.onlineMode() ? AccountType.PREMIUM : AccountType.CRACKED;
        AuthState state = new AuthState(handle.uuid(), handle.username(), request.ipHash(), stage, type);

        if (!commitJoin(handle, state, AuthReason.ADMIN_BYPASS)) {
            return JoinResult.abandoned();
        }
        try {
            auditLog.record("ADMIN_BYPASS_AUTH", nameLower(handle), handle.uuid(), request.ipHash(),
                    "permission=" + context.config().adminBypassPermission());
        } catch (RuntimeException auditFailure) {
            logger.debug("Audit log write for ADMIN_BYPASS_AUTH failed (ignored): {}", auditFailure.getMessage());
        }
        return JoinResult.allowed(true);
    }

    private JoinResult resolvePremiumJoin(ConnectionHandle handle, JoinRequest request) {
        UUID realUuid = handle.uuid();
        String name = handle.username();
        String nameLower = nameLower(handle);
        String ipHash = request.ipHash();
        long now = System.currentTimeMillis();

        Optional<Account> byUuid = repository.findByUuid(realUuid);
        Optional<Account> byName = repository.findByUsername(nameLower);

        // false = row was just created/migrated and already has fresh last_login fields; true = pre-existing.
        boolean alreadyExisted;
        Account account;
        if (byUuid.isPresent()) {
            account = byUuid.get();
            if (account.accountType() != AccountType.PREMIUM) {
                // Provisioning, not a login: the Mojang handshake proved this UUID is premium.
                repository.updateAccountType(account.id(), AccountType.PREMIUM);
                account.setAccountType(AccountType.PREMIUM);
            }
            alreadyExisted = true;
        } else if (byName.isPresent()) {
            account = byName.get();
            if (account.accountType() == AccountType.PENDING_MIGRATION) {
                boolean promoted;
                try {
                    promoted = repository.promotePendingMigrationToPremium(account.id(), realUuid, now, ipHash, name);
                } catch (RuntimeException ex) {
                    logger.error("Atomic premium migration failed for {}: {}", nameLower, ex.getMessage(), ex);
                    auditLog.record("PREMIUM_MIGRATION_FAILED", nameLower, realUuid, ipHash,
                            "exception=" + ex.getClass().getSimpleName());
                    return JoinResult.denied("disconnect.account-state-error");
                }
                if (!promoted) {
                    // Either someone else already migrated this row or it was deleted underneath
                    // us between the lookup and the UPDATE. Refuse to authenticate without proof.
                    logger.warn("Premium migration race: account {} was no longer PENDING_MIGRATION "
                            + "when we tried to promote it.", nameLower);
                    auditLog.record("PREMIUM_MIGRATION_RACE", nameLower, realUuid, ipHash,
                            "row no longer PENDING_MIGRATION");
                    return JoinResult.denied("disconnect.account-state-error");
                }
                account.setUuid(realUuid);
                account.setPremiumUuid(realUuid);
                account.setAccountType(AccountType.PREMIUM);
                account.setLastLoginAt(now);
                account.setLastIpHash(ipHash);
                account.setLastUsername(name);
                account.setFailedAttempts(0);
                account.setLockedUntil(null);
                // Provisioning audit: records the row change, not a completed login.
                auditLog.record("PREMIUM_MIGRATION", nameLower, realUuid, ipHash, "Promoted from PENDING_MIGRATION");
                alreadyExisted = false;
            } else if (account.accountType() == AccountType.CRACKED) {
                auditLog.record("PREMIUM_BLOCKED_COLLISION", nameLower, realUuid, ipHash,
                        "CRACKED row blocks premium join");
                return JoinResult.denied("disconnect.cracked-name-collision");
            } else {
                return JoinResult.denied("disconnect.account-state-error");
            }
        } else {
            account = repository.create(new Account(
                    0L, nameLower, name, AccountType.PREMIUM, realUuid, realUuid, null,
                    now, now, ipHash, 0, null));
            // Provisioning audit: the row now exists whether or not the join completes.
            auditLog.record("PREMIUM_CREATED", nameLower, realUuid, ipHash, null);
            alreadyExisted = false;
        }

        AuthState state = new AuthState(realUuid, name, ipHash,
                AuthState.Stage.AUTHENTICATED_PREMIUM, AccountType.PREMIUM);
        if (!commitJoin(handle, state, AuthReason.PREMIUM)) {
            // Connection gone. The provisioning above stands (see class docs); the success
            // bookkeeping below must not.
            return JoinResult.abandoned();
        }
        if (alreadyExisted) {
            // Refresh last_login_at, last_ip_hash, last_username on every premium join so audit
            // logs stay useful even for unchanged Mojang names. Success bookkeeping: post-commit.
            repository.recordSuccessfulLogin(account.id(), now, ipHash, name);
        }
        auditLog.record("LOGIN_PREMIUM", nameLower, realUuid, ipHash, null);
        return JoinResult.allowed(true);
    }

    private JoinResult resolveCrackedJoin(ConnectionHandle handle, JoinRequest request) {
        UUID uuid = handle.uuid();
        String name = handle.username();
        String nameLower = nameLower(handle);
        String ipHash = request.ipHash();

        Optional<Account> existing = repository.findByUsername(nameLower);
        AuthState.Stage initialStage;
        AccountType type;
        Account sessionAccount = null;
        if (existing.isPresent()) {
            Account account = existing.get();
            if (account.accountType() == AccountType.PREMIUM) {
                auditLog.record("CRACKED_REJECT_ON_PREMIUM_NAME", nameLower, uuid, ipHash, null);
                return JoinResult.denied("disconnect.premium-name-required");
            }
            type = account.accountType();
            if (sessions.findValidSessionExpiry(account.uuid(), ipHash).isPresent()) {
                initialStage = AuthState.Stage.AUTHENTICATED_CRACKED;
                sessionAccount = account;
            } else {
                initialStage = AuthState.Stage.AWAITING_LOGIN;
            }
        } else {
            type = AccountType.CRACKED;
            initialStage = AuthState.Stage.UNREGISTERED;
        }

        AuthState state = new AuthState(uuid, name, ipHash, initialStage, type);
        AuthReason reason = sessionAccount != null ? AuthReason.SESSION : null;
        if (!commitJoin(handle, state, reason)) {
            return JoinResult.abandoned();
        }
        if (sessionAccount != null) {
            // Success bookkeeping for the session auto-login: strictly after the commit point.
            repository.recordSuccessfulLogin(sessionAccount.id(), System.currentTimeMillis(), ipHash, name);
            auditLog.record("LOGIN_SESSION", nameLower, sessionAccount.uuid(), ipHash, "auto-login");
        }
        return JoinResult.allowed(sessionAccount != null);
    }

    /**
     * The join commit point. Attaches the auth state and, for the paths that authenticate straight
     * away, queues the lobby greeting. Both steps report whether the connection was still current;
     * a {@code false} from either aborts the join before any success bookkeeping is written.
     */
    private boolean commitJoin(ConnectionHandle handle, AuthState state, AuthReason reason) {
        if (!authService.trackConnection(handle, state)) {
            logger.debug("Abandoning join for stale connection {}", handle);
            return false;
        }
        if (reason != null && !promptService.markAuthenticated(handle, reason)) {
            logger.debug("Abandoning join for {}: connection ended before the greeting was queued", handle);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ /login

    /** The body of {@code /login}. */
    public Result login(ConnectionHandle handle, String password) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            // Nothing was read and nothing was written: the section was refused before any
            // database call, and only ever because this handle is stale.
            logger.debug("Ignoring /login for stale connection {}", handle);
            return Result.silent();
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            AuthState state = handle.authState().orElse(null);
            if (state == null) {
                return Result.silent();
            }
            AuthService.LoginOutcome outcome = authService.attemptLogin(handle, password, section);
            Result result = switch (outcome) {
                case SUCCESS -> {
                    // Past the linearization point: this login definitively happened, so its records
                    // are written unconditionally, and they stay inside the section so a /logout
                    // issued later cannot have its session invalidation undone by this session row.
                    repository.findByUsername(handle.username()).ifPresent(account ->
                            sessions.createSession(account.id(), handle.uuid(), account.usernameLower(), state.ipHash()));
                    auditLog.record("LOGIN", nameLower(handle), handle.uuid(), state.ipHash(), null);
                    yield finishAuthenticated(handle, AuthReason.MANUAL_LOGIN, "login.success");
                }
                case WRONG_PASSWORD -> {
                    auditLog.record("LOGIN_FAIL", nameLower(handle), handle.uuid(), state.ipHash(), "wrong-password");
                    yield Result.message("login.wrong-password");
                }
                case ACCOUNT_LOCKED -> Result.message("login.account-locked");
                case ACCOUNT_NOT_FOUND -> Result.message("login.not-registered");
                case RATE_LIMITED -> Result.message("error.rate-limited");
                case CONNECTION_STALE -> {
                    logger.debug("Ignoring /login result for stale connection {}", handle);
                    yield Result.silent();
                }
            };
            return result.at(section.stamp());
        }
    }

    // ------------------------------------------------------------------ /register

    /** The body of {@code /register}, including the premium-name gate. */
    public Result register(ConnectionHandle handle, String password, String confirm) {
        if (handle.authState().isEmpty()) {
            return Result.silent();
        }
        // The Mojang lookup depends on the name alone, never on our own rows, so it deliberately
        // runs outside the ordered section: parking a reconnect behind a slow HTTP call would buy
        // nothing. Everything that reads or writes the account row happens inside - and so does
        // turning the lookup into an answer, so even the "check unavailable" reply carries the
        // ordering stamp it needs to reach the player.
        boolean premiumCheckUnavailable = false;
        boolean nameIsPremium = false;
        if (context.config().premium().enabled()) {
            PremiumResolver.Result premium = premiumResolver.resolve(handle.username());
            premiumCheckUnavailable = premium.isUnknown() && !context.config().premium().failOpenOnCheckError();
            nameIsPremium = premium.isPremium();
        }
        // Offline-only mode never calls Mojang from /register and treats every name as cracked.

        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            logger.debug("Ignoring /register for stale connection {}", handle);
            return Result.silent();
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            AuthState state = handle.authState().orElse(null);
            if (state == null) {
                return Result.silent();
            }
            if (premiumCheckUnavailable) {
                return Result.message("register.premium-check-unavailable").at(section.stamp());
            }
            AuthService.RegisterOutcome outcome =
                    authService.attemptRegister(handle, password, confirm, nameIsPremium, section);
            Result result = switch (outcome) {
                case SUCCESS -> {
                    repository.findByUuid(handle.uuid()).ifPresent(account ->
                            sessions.createSession(account.id(), handle.uuid(), account.usernameLower(), state.ipHash()));
                    auditLog.record("REGISTER", nameLower(handle), handle.uuid(), state.ipHash(), null);
                    yield finishAuthenticated(handle, AuthReason.REGISTER, "register.success");
                }
                case PASSWORD_MISMATCH -> Result.message("register.password-mismatch");
                case PASSWORD_TOO_SHORT ->
                        Result.message("register.password-too-short", context.config().security().minPasswordLength());
                case ALREADY_REGISTERED -> Result.message("register.already-registered");
                case TOO_MANY_ACCOUNTS_FOR_IP -> Result.message("register.too-many-accounts");
                case RATE_LIMITED -> Result.message("error.rate-limited");
                case PREMIUM_NAME_PROTECTED -> Result.message("register.premium-name-protected");
                case CONNECTION_STALE -> {
                    logger.debug("Ignoring /register result for stale connection {}", handle);
                    yield Result.silent();
                }
            };
            return result.at(section.stamp());
        }
    }

    /**
     * Shared tail of a successful {@code /login} or {@code /register}: tear the limbo prompt down,
     * queue the greeting, and only tell/route the player when their connection is still live.
     */
    private Result finishAuthenticated(ConnectionHandle handle, AuthReason reason, String successKey) {
        if (!promptService.onAuthenticated(handle, reason)) {
            // Committed, but the player is already gone. Their records above stand; there is
            // nobody left to greet or move.
            logger.debug("Authentication for {} committed after the connection ended; suppressing UI", handle);
            return Result.silent();
        }
        return Result.messageAndRoute(successKey, Routing.TARGET);
    }

    // ------------------------------------------------------------------ /logout

    /** The body of {@code /logout}. */
    public Result logout(ConnectionHandle handle) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return Result.silent();
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            Result result = switch (endAuthenticatedSession(handle, section, "LOGOUT")) {
                case SUCCESS -> Result.messageAndRoute("logout.success", Routing.LIMBO);
                case PREMIUM_NOT_SUPPORTED -> Result.message("logout.premium-not-supported");
                case NO_STATE -> Result.silent();
            };
            return result.at(section.stamp());
        }
    }

    /**
     * The staff-initiated counterpart of {@link #logout}, for {@code /hexlimbo forcelogout}.
     *
     * <p>Deliberately the same production lifecycle: demotion, session invalidation and prompt
     * teardown happen in one ordered section, so a forced logout beats a {@code /login} that is
     * still committing exactly the way the player's own {@code /logout} does. What differs is only
     * the reporting - the staff command writes its own {@code ADMIN_FORCE_LOGOUT} entry and picks
     * its own player-facing effect, so no {@code LOGOUT} entry is recorded here.
     *
     * <p>The returned {@link ForcedLogout} carries the ordering stamp, so that effect is applied
     * under the same rule as every other flow result: dropped if a later operation on the same
     * connection has already taken effect.
     */
    public ForcedLogout forceLogout(ConnectionHandle handle) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            return new ForcedLogout(AuthService.LogoutOutcome.NO_STATE, Optional.empty());
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            AuthService.LogoutOutcome outcome = endAuthenticatedSession(handle, section, null);
            return new ForcedLogout(outcome, Optional.of(section.stamp()));
        }
    }

    /**
     * Demotes the connection and tears its authenticated bookkeeping down inside the caller's
     * ordered section. Holding the section across all three steps is what stops a login that
     * acquired it earlier from re-creating the session this logout just invalidated, or from
     * leaving its success greeting queued for a player who is on their way back to the limbo.
     *
     * @param auditAction the audit entry to write on success, or {@code null} when the caller
     *                    records its own
     */
    private AuthService.LogoutOutcome endAuthenticatedSession(
            ConnectionHandle handle, ConnectionRegistry.CommitLease section, String auditAction) {
        AuthService.LogoutOutcome outcome = authService.logout(handle, section);
        if (outcome != AuthService.LogoutOutcome.SUCCESS) {
            return outcome;
        }
        sessions.invalidate(handle.uuid());
        // Drop the prompt and any queued greeting but keep the connection: the player stays
        // online and gets the login prompt again once they land back in the limbo.
        promptService.clearPrompt(handle);
        if (auditAction != null) {
            auditLog.record(auditAction, nameLower(handle), handle.uuid(), null, null);
        }
        return outcome;
    }

    // ------------------------------------------------------------------ /changepassword

    /** The body of {@code /changepassword}. */
    public Result changePassword(ConnectionHandle handle, String oldPassword, String newPassword) {
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            logger.debug("Ignoring /changepassword for stale connection {}", handle);
            return Result.silent();
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            if (!authService.changePassword(handle, oldPassword, newPassword, section)) {
                // The credentials were wrong; nothing was written.
                return section.isCurrent()
                        ? Result.message("changepassword.failed").at(section.stamp())
                        : Result.silent();
            }
            sessions.invalidate(handle.uuid());
            auditLog.record("CHANGE_PASSWORD", nameLower(handle), handle.uuid(), null, null);
            // As with /premium, the currency check keeps the returned Result itself honest; the
            // stamp is what additionally stops it from overtaking a later operation at apply time.
            return section.isCurrent()
                    ? Result.message("changepassword.success").at(section.stamp())
                    : Result.silent();
        }
    }

    // ------------------------------------------------------------------ /premium

    /** The body of {@code /premium}: request a migration of a cracked account to premium. */
    public Result requestPremiumMigration(ConnectionHandle handle) {
        // Persistent mutation, so the whole thing - the account lookup that decides it as well as
        // the write - goes through the same ordered section as the auth writes: a player who left
        // before it started must not have their account type changed, and a concurrent join or
        // login for the same account must not read a type this call is about to replace.
        ConnectionRegistry.CommitLease lease = connections.beginCommit(handle).orElse(null);
        if (lease == null) {
            logger.debug("Ignoring /premium for stale connection {}", handle);
            return Result.silent();
        }
        try (ConnectionRegistry.CommitLease section = lease) {
            Optional<Account> opt = repository.findByUuid(handle.uuid());
            if (opt.isEmpty()) {
                return Result.silent();
            }
            Account account = opt.get();
            if (account.accountType() == AccountType.PREMIUM) {
                return Result.message("premium.already-premium").at(section.stamp());
            }
            if (account.accountType() == AccountType.PENDING_MIGRATION) {
                return Result.message("premium.already-requested").at(section.stamp());
            }
            repository.updateAccountType(account.id(), AccountType.PENDING_MIGRATION);
            auditLog.record("PREMIUM_REQUEST", account.usernameLower(), handle.uuid(), null, null);
            section.commit(() -> { });
            // Committed: the PENDING_MIGRATION type and the audit entry stand whatever happens next.
            // The confirmation does not - a player who disconnected, or whose socket was replaced,
            // while the write was in flight must not be written to, exactly as for /login,
            // /register and /changepassword. The stamp enforces the same thing once more at apply
            // time; this check is what makes the returned Result itself honest.
            if (!section.isCurrent()) {
                logger.debug("/premium for {} committed after the connection ended; suppressing UI", handle);
                return Result.silent();
            }
            return Result.message("premium.requested").at(section.stamp());
        }
    }

    private static String nameLower(ConnectionHandle handle) {
        return handle.username() == null ? "" : handle.username().toLowerCase(Locale.ROOT);
    }
}
