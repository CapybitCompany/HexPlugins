package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.SessionService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.limbo.LimboRouter;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.concurrent.Executor;

public final class LogoutCommand implements SimpleCommand {

    private final AuthService authService;
    private final SessionService sessionService;
    private final LimboRouter router;
    private final RuntimeContext context;
    private final AuditLogService auditLog;
    private final Executor authExecutor;
    private final Logger logger;

    public LogoutCommand(
            AuthService authService,
            SessionService sessionService,
            LimboRouter router,
            RuntimeContext context,
            AuditLogService auditLog,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.router = router;
        this.context = context;
        this.auditLog = auditLog;
        this.authExecutor = authExecutor;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text(context.messages().raw("error.players-only")));
            return;
        }
        authExecutor.execute(() -> {
            try {
                AuthService.LogoutOutcome outcome = authService.logout(player.getUniqueId());
                switch (outcome) {
                    case SUCCESS -> {
                        sessionService.invalidate(player.getUniqueId());
                        player.sendMessage(Component.text(context.messages().raw("logout.success")));
                        auditLog.record("LOGOUT", player.getUsername().toLowerCase(), player.getUniqueId(), null, null);
                        router.sendToLimbo(player);
                    }
                    case PREMIUM_NOT_SUPPORTED ->
                            player.sendMessage(Component.text(context.messages().raw("logout.premium-not-supported")));
                    case NO_STATE -> {
                        // Player has no live auth state (extremely rare race). Nothing to do.
                    }
                }
            } catch (RuntimeException ex) {
                logger.error("/logout failed asynchronously for {}", player.getUsername(), ex);
                player.sendMessage(Component.text(context.messages().raw("error.internal")));
            }
        });
    }
}
