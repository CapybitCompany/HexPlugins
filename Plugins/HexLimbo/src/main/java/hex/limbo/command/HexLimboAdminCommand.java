package hex.limbo.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import hex.limbo.HexLimboPlugin;
import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.auth.SessionService;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.limbo.LimboRouter;
import hex.limbo.premium.PremiumResolverHandle;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Staff control: {@code /hexlimbo <reload|info|resetpassword|forcelogout|unregister|sessions|migrate|debug>}.
 * Permissions required: {@code hexlimbo.admin}. All DB-touching subcommands run on the auth
 * executor so the netty command thread is free.
 */
public final class HexLimboAdminCommand implements SimpleCommand {

    private static final String ADMIN_PERMISSION = "hexlimbo.admin";

    private final HexLimboPlugin plugin;
    private final ProxyServer proxy;
    private final AuthService authService;
    private final AccountRepository repository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final LimboRouter router;
    private final RuntimeContext context;
    private final PremiumResolverHandle premiumResolver;
    private final AuditLogService auditLog;
    private final Executor authExecutor;
    private final Logger logger;

    public HexLimboAdminCommand(
            HexLimboPlugin plugin,
            ProxyServer proxy,
            AuthService authService,
            AccountRepository repository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            LimboRouter router,
            RuntimeContext context,
            PremiumResolverHandle premiumResolver,
            AuditLogService auditLog,
            Executor authExecutor,
            Logger logger
    ) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.authService = authService;
        this.repository = repository;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.router = router;
        this.context = context;
        this.premiumResolver = premiumResolver;
        this.auditLog = auditLog;
        this.authExecutor = authExecutor;
        this.logger = logger;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ADMIN_PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        MessagesConfig messages = context.messages();
        if (args.length == 0) {
            source.sendMessage(Component.text(messages.raw("admin.usage")));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> dispatch(() -> handleReload(source));
            case "info" -> dispatch(() -> handleInfo(source, args));
            case "resetpassword" -> dispatch(() -> handleResetPassword(source, args));
            case "forcelogout" -> dispatch(() -> handleForceLogout(source, args));
            case "unregister" -> dispatch(() -> handleUnregister(source, args));
            case "sessions" -> dispatch(() -> handleSessions(source, args));
            case "migrate" -> dispatch(() -> handleMigrate(source, args));
            case "debug" -> handleDebug(source, args); // pure in-memory lookup, fine synchronously
            default -> source.sendMessage(Component.text(messages.raw("admin.usage")));
        }
    }

    private void dispatch(Runnable runnable) {
        authExecutor.execute(() -> {
            try {
                runnable.run();
            } catch (RuntimeException ex) {
                logger.error("/hexlimbo admin command failed asynchronously", ex);
            }
        });
    }

    private void handleReload(CommandSource source) {
        MessagesConfig messages = context.messages();
        try {
            plugin.reloadConfiguration();
            premiumResolver.clear();
            source.sendMessage(Component.text(context.messages().raw("admin.reload.success")));
        } catch (Exception ex) {
            source.sendMessage(Component.text(messages.format("admin.reload.failed", ex.getMessage())));
        }
    }

    private void handleInfo(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.info")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account a = opt.get();
        source.sendMessage(Component.text(messages.format("admin.info.header", a.lastUsername())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "id", a.id())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "type", a.accountType())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "uuid", a.uuid())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "premiumUuid", a.premiumUuid())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "registeredAt", a.registeredAt())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "lastLoginAt", a.lastLoginAt())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "failedAttempts", a.failedAttempts())));
        source.sendMessage(Component.text(messages.format("admin.info.field", "lockedUntil", a.lockedUntil())));
    }

    private void handleResetPassword(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 3) {
            source.sendMessage(Component.text(messages.raw("admin.usage.resetpassword")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account account = opt.get();
        repository.updatePasswordHash(account.id(), passwordHasher.hash(args[2]));
        sessionService.invalidate(account.uuid());
        auditLog.record("ADMIN_RESET_PASSWORD", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(Component.text(messages.raw("admin.resetpassword.success")));
    }

    private void handleForceLogout(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.forcelogout")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account account = opt.get();
        sessionService.invalidate(account.uuid());

        Optional<Player> online = proxy.getPlayer(account.uuid());
        if (online.isEmpty()) {
            auditLog.record("ADMIN_FORCE_LOGOUT", account.usernameLower(), account.uuid(), null, sourceLabel(source) + " (offline)");
            source.sendMessage(Component.text(messages.raw("admin.forcelogout.offline-sessions-invalidated")));
            return;
        }

        Player player = online.get();
        AuthService.LogoutOutcome outcome = authService.logout(player.getUniqueId());
        String resultKey;
        if (outcome == AuthService.LogoutOutcome.PREMIUM_NOT_SUPPORTED) {
            // Premium player has no password – send them off with a clear kick instead of routing
            // them to limbo where they could never re-authenticate.
            player.disconnect(Component.text(messages.raw("admin.forcelogout.kick-premium")));
            resultKey = "admin.forcelogout.kicked-premium";
        } else {
            router.sendToLimbo(player);
            resultKey = "admin.forcelogout.sent-limbo";
        }
        auditLog.record("ADMIN_FORCE_LOGOUT", account.usernameLower(), account.uuid(), null, sourceLabel(source) + " outcome=" + outcome);
        source.sendMessage(Component.text(messages.format(resultKey, player.getUsername())));
    }

    private void handleUnregister(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.unregister")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account account = opt.get();
        sessionService.invalidate(account.uuid());
        proxy.getPlayer(account.uuid()).ifPresent(p ->
                p.disconnect(Component.text(messages.raw("admin.unregister.kick"))));
        repository.delete(account.id());
        auditLog.record("ADMIN_UNREGISTER", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(Component.text(messages.raw("admin.unregister.success")));
    }

    private void handleSessions(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.sessions")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account account = opt.get();
        int count = sessionService.countValidSessionsForUuid(account.uuid());
        if (count == 0) {
            source.sendMessage(Component.text(messages.raw("admin.sessions.none")));
            return;
        }
        Optional<Long> latest = sessionService.findLatestExpiryForUuid(account.uuid());
        source.sendMessage(Component.text(messages.format("admin.sessions.count", count)));
        latest.ifPresent(expiry -> source.sendMessage(Component.text(messages.format("admin.sessions.expiry", expiry))));
    }

    private void handleMigrate(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.migrate")));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.account-not-found")));
            return;
        }
        Account account = opt.get();
        repository.updateAccountType(account.id(), AccountType.PENDING_MIGRATION);
        auditLog.record("ADMIN_MARK_PENDING_MIGRATION", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(Component.text(messages.raw("admin.migrate.marked")));
    }

    private void handleDebug(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(Component.text(messages.raw("admin.usage.debug")));
            return;
        }
        Optional<Player> player = proxy.getPlayer(args[1]);
        if (player.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.player-not-online")));
            return;
        }
        UUID uuid = player.get().getUniqueId();
        Optional<AuthState> state = authService.stateOf(uuid);
        if (state.isEmpty()) {
            source.sendMessage(Component.text(messages.raw("admin.debug.no-state")));
            return;
        }
        AuthState s = state.get();
        source.sendMessage(Component.text(messages.format("admin.debug.header", player.get().getUsername())));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "uuid", uuid)));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "stage", s.stage())));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "type", s.accountType())));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "ipHash", s.ipHash())));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "joinedAt", s.joinedAt())));
        source.sendMessage(Component.text(messages.format("admin.debug.field", "onlineMode", player.get().isOnlineMode())));
    }

    private String sourceLabel(CommandSource source) {
        if (source instanceof Player p) {
            return "by player " + p.getUsername();
        }
        return "by console";
    }
}
