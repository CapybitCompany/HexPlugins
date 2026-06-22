package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.SessionService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.concurrent.Executor;

public final class ChangePasswordCommand implements SimpleCommand {

    private final AuthService authService;
    private final SessionService sessionService;
    private final RuntimeContext context;
    private final AuditLogService auditLog;
    private final Executor authExecutor;
    private final Logger logger;

    public ChangePasswordCommand(
            AuthService authService,
            SessionService sessionService,
            RuntimeContext context,
            AuditLogService auditLog,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.sessionService = sessionService;
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
        if (!authService.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text(context.messages().raw("error.must-authenticate-first")));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(Component.text(context.messages().raw("changepassword.usage")));
            return;
        }
        String oldPw = args[0];
        String newPw = args[1];
        authExecutor.execute(() -> {
            try {
                boolean changed = authService.changePassword(player.getUniqueId(), oldPw, newPw);
                if (changed) {
                    player.sendMessage(Component.text(context.messages().raw("changepassword.success")));
                    sessionService.invalidate(player.getUniqueId());
                    auditLog.record("CHANGE_PASSWORD", player.getUsername().toLowerCase(), player.getUniqueId(), null, null);
                } else {
                    player.sendMessage(Component.text(context.messages().raw("changepassword.failed")));
                }
            } catch (RuntimeException ex) {
                logger.error("/changepassword failed asynchronously for {}", player.getUsername(), ex);
                player.sendMessage(Component.text(context.messages().raw("error.internal")));
            }
        });
    }
}
