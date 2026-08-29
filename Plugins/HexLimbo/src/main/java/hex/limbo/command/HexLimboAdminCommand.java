package hex.limbo.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import hex.limbo.HexLimboPlugin;
import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.FlowResultApplier;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.auth.SessionService;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.limbo.LimboServer;
import hex.limbo.limbo.server.Protocol;
import hex.limbo.premium.PremiumResolverHandle;
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
    private final AuthFlow flow;
    private final AccountRepository repository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final PremiumResolverHandle premiumResolver;
    private final AuditLogService auditLog;
    private final LimboServer limboServer;
    private final Executor authExecutor;
    private final Logger logger;

    public HexLimboAdminCommand(
            HexLimboPlugin plugin,
            ProxyServer proxy,
            AuthService authService,
            AuthFlow flow,
            AccountRepository repository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            RouteCoordinator routes,
            RuntimeContext context,
            PremiumResolverHandle premiumResolver,
            AuditLogService auditLog,
            LimboServer limboServer,
            Executor authExecutor,
            Logger logger
    ) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.authService = authService;
        this.flow = flow;
        this.repository = repository;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.routes = routes;
        this.context = context;
        this.premiumResolver = premiumResolver;
        this.auditLog = auditLog;
        this.limboServer = limboServer;
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
            source.sendMessage(messages.component("admin.usage"));
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
            case "limbo" -> handleLimboStatus(source); // pure in-memory lookup, fine synchronously
            default -> source.sendMessage(messages.component("admin.usage"));
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
            source.sendMessage(context.messages().component("admin.reload.success"));
        } catch (Exception ex) {
            source.sendMessage(messages.component("admin.reload.failed", ex.getMessage()));
        }
    }

    private void handleInfo(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.info"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account a = opt.get();
        source.sendMessage(messages.component("admin.info.header", a.lastUsername()));
        source.sendMessage(messages.component("admin.info.field", "id", a.id()));
        source.sendMessage(messages.component("admin.info.field", "type", a.accountType()));
        source.sendMessage(messages.component("admin.info.field", "uuid", a.uuid()));
        source.sendMessage(messages.component("admin.info.field", "premiumUuid", a.premiumUuid()));
        source.sendMessage(messages.component("admin.info.field", "registeredAt", a.registeredAt()));
        source.sendMessage(messages.component("admin.info.field", "lastLoginAt", a.lastLoginAt()));
        source.sendMessage(messages.component("admin.info.field", "failedAttempts", a.failedAttempts()));
        source.sendMessage(messages.component("admin.info.field", "lockedUntil", a.lockedUntil()));
    }

    private void handleResetPassword(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 3) {
            source.sendMessage(messages.component("admin.usage.resetpassword"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account account = opt.get();
        repository.updatePasswordHash(account.id(), passwordHasher.hash(args[2]));
        sessionService.invalidate(account.uuid());
        auditLog.record("ADMIN_RESET_PASSWORD", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(messages.component("admin.resetpassword.success"));
    }

    private void handleForceLogout(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.forcelogout"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account account = opt.get();
        sessionService.invalidate(account.uuid());

        Optional<Player> online = proxy.getPlayer(account.uuid());
        if (online.isEmpty()) {
            auditLog.record("ADMIN_FORCE_LOGOUT", account.usernameLower(), account.uuid(), null, sourceLabel(source) + " (offline)");
            source.sendMessage(messages.component("admin.forcelogout.offline-sessions-invalidated"));
            return;
        }

        // Through AuthFlow, not straight to AuthService: the forced logout has to be ordered
        // against a /login that is still committing, and it is AuthFlow that owns that section
        // together with the session invalidation the login would otherwise re-create. The blanket
        // invalidate above covers the offline and premium cases; this one is the ordered version
        // for a player who really is demoted.
        Player player = online.get();
        AuthFlow.ForcedLogout forced = authService.connections()
                .currentFor(player.getUniqueId(), player)
                .map(flow::forceLogout)
                .orElseGet(() -> new AuthFlow.ForcedLogout(AuthService.LogoutOutcome.NO_STATE, Optional.empty()));
        AuthService.LogoutOutcome outcome = forced.outcome();
        // Applied under the logout's own ordering stamp: if the player logged back in between the
        // demotion and this line, the kick or the trip to limbo is dropped rather than yanking an
        // authenticated player back out of the target server.
        FlowResultApplier.Application applied = authService.connections()
                .currentFor(player.getUniqueId(), player)
                .map(handle -> FlowCommandSupport.apply(
                        forced.playerEffect(), authService, routes, handle, player, context))
                .orElse(FlowResultApplier.Application.of(ConnectionRegistry.ApplyOutcome.STALE_CONNECTION));

        // Reported only once the transfer has actually settled. Applying a routing decision is not
        // the same as arriving: it can still be queued behind another transfer, be superseded by a
        // login, or fail outright. No commit slot is held while we wait - the stage is completed by
        // the coordinator's own callbacks, and it always completes.
        applied.routing()
                .map(stage -> stage.thenAccept(route ->
                        reportForceLogout(source, player, account, forced, applied, route)))
                .orElseGet(() -> {
                    reportForceLogout(source, player, account, forced, applied, null);
                    return null;
                });
    }

    /**
     * Tells the staff member what really became of the player, and records both halves - the auth
     * demotion and what happened to its visible effect - in the audit entry.
     */
    private void reportForceLogout(
            CommandSource source,
            Player player,
            Account account,
            AuthFlow.ForcedLogout forced,
            FlowResultApplier.Application applied,
            RouteCoordinator.RouteResult route
    ) {
        auditLog.record("ADMIN_FORCE_LOGOUT", account.usernameLower(), account.uuid(), null,
                sourceLabel(source) + " auth=" + forced.outcome()
                        + " effect=" + applied.outcome() + " route=" + route);
        source.sendMessage(context.messages()
                .component(forced.staffMessageKey(applied.outcome(), route), player.getUsername()));
    }

    private void handleUnregister(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.unregister"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account account = opt.get();
        sessionService.invalidate(account.uuid());
        proxy.getPlayer(account.uuid()).ifPresent(p ->
                p.disconnect(messages.component("admin.unregister.kick")));
        repository.delete(account.id());
        auditLog.record("ADMIN_UNREGISTER", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(messages.component("admin.unregister.success"));
    }

    private void handleSessions(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.sessions"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account account = opt.get();
        int count = sessionService.countValidSessionsForUuid(account.uuid());
        if (count == 0) {
            source.sendMessage(messages.component("admin.sessions.none"));
            return;
        }
        Optional<Long> latest = sessionService.findLatestExpiryForUuid(account.uuid());
        source.sendMessage(messages.component("admin.sessions.count", count));
        latest.ifPresent(expiry -> source.sendMessage(messages.component("admin.sessions.expiry", expiry)));
    }

    private void handleMigrate(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.migrate"));
            return;
        }
        Optional<Account> opt = repository.findByUsername(args[1]);
        if (opt.isEmpty()) {
            source.sendMessage(messages.component("admin.account-not-found"));
            return;
        }
        Account account = opt.get();
        repository.updateAccountType(account.id(), AccountType.PENDING_MIGRATION);
        auditLog.record("ADMIN_MARK_PENDING_MIGRATION", account.usernameLower(), account.uuid(), null, sourceLabel(source));
        source.sendMessage(messages.component("admin.migrate.marked"));
    }

    private void handleDebug(CommandSource source, String[] args) {
        MessagesConfig messages = context.messages();
        if (args.length < 2) {
            source.sendMessage(messages.component("admin.usage.debug"));
            return;
        }
        Optional<Player> player = proxy.getPlayer(args[1]);
        if (player.isEmpty()) {
            source.sendMessage(messages.component("admin.player-not-online"));
            return;
        }
        UUID uuid = player.get().getUniqueId();
        Optional<AuthState> state = authService.stateOf(uuid);
        if (state.isEmpty()) {
            source.sendMessage(messages.component("admin.debug.no-state"));
            return;
        }
        AuthState s = state.get();
        source.sendMessage(messages.component("admin.debug.header", player.get().getUsername()));
        source.sendMessage(messages.component("admin.debug.field", "uuid", uuid));
        source.sendMessage(messages.component("admin.debug.field", "stage", s.stage()));
        source.sendMessage(messages.component("admin.debug.field", "type", s.accountType()));
        source.sendMessage(messages.component("admin.debug.field", "ipHash", s.ipHash()));
        source.sendMessage(messages.component("admin.debug.field", "joinedAt", s.joinedAt()));
        source.sendMessage(messages.component("admin.debug.field", "onlineMode", player.get().isOnlineMode()));
    }

    private void handleLimboStatus(CommandSource source) {
        MessagesConfig messages = context.messages();
        boolean ready = limboServer.isReady();
        source.sendMessage(messages.component("admin.limbo.status", ready ? "ready" : "not ready"));
        source.sendMessage(messages.component("admin.limbo.field", "server-name", limboServer.serverName()));
        source.sendMessage(messages.component("admin.limbo.field", "bind-host", limboServer.bindHost()));
        source.sendMessage(messages.component("admin.limbo.field", "bind-port", limboServer.bindPort()));
        source.sendMessage(messages.component("admin.limbo.field", "supported-protocol",
                Protocol.MINECRAFT_VERSION_LABEL + " (id " + Protocol.MINECRAFT_PROTOCOL_VERSION + ")"));
        source.sendMessage(messages.component("admin.limbo.field", "ready", ready));
        source.sendMessage(messages.component("admin.limbo.field", "active-sessions", limboServer.activeConnectionCount()));
        source.sendMessage(messages.component("admin.limbo.field", "tcp-connections", limboServer.tcpConnectionCount()));
        String lastErr = limboServer.lastStartError().orElseGet(() -> messages.raw("admin.limbo.no-error"));
        source.sendMessage(messages.component("admin.limbo.field", "last-start-error", lastErr));
    }

    private String sourceLabel(CommandSource source) {
        if (source instanceof Player p) {
            return "by player " + p.getUsername();
        }
        return "by console";
    }
}
