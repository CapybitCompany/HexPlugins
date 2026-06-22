package hex.limbo.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.SessionService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.security.IpHasher;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Wires up per-connection auth state, kicks for premium-name collisions, applies session
 * auto-login, schedules the login timeout, and migrates PENDING_MIGRATION rows to PREMIUM when the
 * actual premium owner finally arrives.
 *
 * <p>All DB-touching work happens inside {@link EventTask#async(Runnable)} so the netty thread is
 * never blocked. Players with the configured admin-bypass permission skip the entire auth flow.
 */
public final class LoginListener {

    private final ProxyServer proxy;
    private final Object plugin;
    private final AuthService authService;
    private final SessionService sessionService;
    private final AccountRepository repository;
    private final IpHasher ipHasher;
    private final RuntimeContext context;
    private final AuditLogService auditLog;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, ScheduledTask> loginTimers = new ConcurrentHashMap<>();

    public LoginListener(
            ProxyServer proxy,
            Object plugin,
            AuthService authService,
            SessionService sessionService,
            AccountRepository repository,
            IpHasher ipHasher,
            RuntimeContext context,
            AuditLogService auditLog,
            Logger logger
    ) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.authService = authService;
        this.sessionService = sessionService;
        this.repository = repository;
        this.ipHasher = ipHasher;
        this.context = context;
        this.auditLog = auditLog;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        return EventTask.async(() -> handleLogin(event, player));
    }

    private void handleLogin(LoginEvent event, Player player) {
        String name = player.getUsername();
        String nameLower = name.toLowerCase(Locale.ROOT);
        String ipHash = ipHasher.hash(player.getRemoteAddress().getAddress().getHostAddress());
        boolean onlineMode = player.isOnlineMode();
        UUID uuid = player.getUniqueId();

        // Admin bypass is checked *before* any DB / session / account-collision logic so a bypass
        // user can still get in when MySQL is degraded or the DB has stale collision rows.
        if (player.hasPermission(context.config().adminBypassPermission())) {
            AuthState.Stage stage = onlineMode ? AuthState.Stage.AUTHENTICATED_PREMIUM : AuthState.Stage.AUTHENTICATED_CRACKED;
            AccountType type = onlineMode ? AccountType.PREMIUM : AccountType.CRACKED;
            authService.trackConnection(new AuthState(uuid, name, ipHash, stage, type));
            try {
                auditLog.record("ADMIN_BYPASS_AUTH", nameLower, uuid, ipHash,
                        "permission=" + context.config().adminBypassPermission());
            } catch (RuntimeException auditFailure) {
                logger.debug("Audit log write for ADMIN_BYPASS_AUTH failed (ignored): {}", auditFailure.getMessage());
            }
            return;
        }

        try {
            if (onlineMode) {
                handlePremiumLogin(event, player, name, nameLower, ipHash);
            } else {
                handleCrackedLogin(event, player, name, nameLower, ipHash);
            }
        } catch (RuntimeException ex) {
            logger.error("HexLimbo login pipeline failed for {}: {}", name, ex.getMessage(), ex);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    Component.text(context.messages().raw("disconnect.account-state-error"))));
            return;
        }

        AuthState state = authService.stateOf(uuid).orElse(null);
        if (state != null && !state.isAuthenticated()) {
            scheduleLoginTimeout(player.getUniqueId());
        }
    }

    private void handlePremiumLogin(LoginEvent event, Player player, String name, String nameLower, String ipHash) {
        UUID realUuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Optional<Account> byUuid = repository.findByUuid(realUuid);
        Optional<Account> byName = repository.findByUsername(nameLower);

        // false = row was just created/migrated and already has fresh last_login fields; true = pre-existing.
        boolean alreadyExisted;
        Account account;
        if (byUuid.isPresent()) {
            account = byUuid.get();
            if (account.accountType() != AccountType.PREMIUM) {
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
                    auditLog.record("PREMIUM_MIGRATION_FAILED", nameLower, realUuid, ipHash, "exception=" + ex.getClass().getSimpleName());
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            Component.text(context.messages().raw("disconnect.account-state-error"))));
                    return;
                }
                if (!promoted) {
                    // Either someone else already migrated this row or it was deleted underneath
                    // us between the lookup and the UPDATE. Refuse to authenticate without proof.
                    logger.warn("Premium migration race: account {} was no longer PENDING_MIGRATION when we tried to promote it.", nameLower);
                    auditLog.record("PREMIUM_MIGRATION_RACE", nameLower, realUuid, ipHash, "row no longer PENDING_MIGRATION");
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            Component.text(context.messages().raw("disconnect.account-state-error"))));
                    return;
                }
                account.setUuid(realUuid);
                account.setPremiumUuid(realUuid);
                account.setAccountType(AccountType.PREMIUM);
                account.setLastLoginAt(now);
                account.setLastIpHash(ipHash);
                account.setLastUsername(name);
                account.setFailedAttempts(0);
                account.setLockedUntil(null);
                auditLog.record("PREMIUM_MIGRATION", nameLower, realUuid, ipHash, "Promoted from PENDING_MIGRATION");
                alreadyExisted = false;
            } else if (account.accountType() == AccountType.CRACKED) {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(context.messages().raw("disconnect.cracked-name-collision"))));
                auditLog.record("PREMIUM_BLOCKED_COLLISION", nameLower, realUuid, ipHash, "CRACKED row blocks premium join");
                return;
            } else {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(context.messages().raw("disconnect.account-state-error"))));
                return;
            }
        } else {
            account = repository.create(new Account(
                    0L,
                    nameLower,
                    name,
                    AccountType.PREMIUM,
                    realUuid,
                    realUuid,
                    null,
                    now,
                    now,
                    ipHash,
                    0,
                    null
            ));
            auditLog.record("PREMIUM_CREATED", nameLower, realUuid, ipHash, null);
            alreadyExisted = false;
        }

        if (alreadyExisted) {
            // Refresh last_login_at, last_ip_hash, last_username on every premium join so audit
            // logs stay useful even for unchanged Mojang names.
            repository.recordSuccessfulLogin(account.id(), now, ipHash, name);
        }

        AuthState state = new AuthState(realUuid, name, ipHash, AuthState.Stage.AUTHENTICATED_PREMIUM, AccountType.PREMIUM);
        authService.trackConnection(state);
        auditLog.record("LOGIN_PREMIUM", nameLower, realUuid, ipHash, null);
    }

    private void handleCrackedLogin(LoginEvent event, Player player, String name, String nameLower, String ipHash) {
        UUID uuid = player.getUniqueId();
        Optional<Account> existing = repository.findByUsername(nameLower);

        AuthState.Stage initialStage;
        AccountType type;
        if (existing.isPresent()) {
            Account account = existing.get();
            if (account.accountType() == AccountType.PREMIUM) {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(context.messages().raw("disconnect.premium-name-required"))));
                auditLog.record("CRACKED_REJECT_ON_PREMIUM_NAME", nameLower, uuid, ipHash, null);
                return;
            }
            type = account.accountType();
            Optional<Long> session = sessionService.findValidSessionExpiry(account.uuid(), ipHash);
            if (session.isPresent()) {
                initialStage = AuthState.Stage.AUTHENTICATED_CRACKED;
                repository.recordSuccessfulLogin(account.id(), System.currentTimeMillis(), ipHash, name);
                auditLog.record("LOGIN_SESSION", nameLower, account.uuid(), ipHash, "auto-login");
            } else {
                initialStage = AuthState.Stage.AWAITING_LOGIN;
            }
        } else {
            type = AccountType.CRACKED;
            initialStage = AuthState.Stage.UNREGISTERED;
        }

        AuthState state = new AuthState(uuid, name, ipHash, initialStage, type);
        authService.trackConnection(state);
    }

    private void scheduleLoginTimeout(UUID uuid) {
        long timeoutSeconds = Math.max(10L, context.config().loginTimeoutSeconds());
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> {
            if (!authService.isAuthenticated(uuid)) {
                proxy.getPlayer(uuid).ifPresent(p -> p.disconnect(
                        Component.text(context.messages().raw("disconnect.login-timeout"))));
            }
        }).delay(timeoutSeconds, TimeUnit.SECONDS).schedule();
        loginTimers.put(uuid, task);
    }

    public void cancelLoginTimer(UUID uuid) {
        ScheduledTask task = loginTimers.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
}
