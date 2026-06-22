package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.AccountType;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.Executor;

public final class PremiumCommand implements SimpleCommand {

    private final AuthService authService;
    private final AccountRepository repository;
    private final RuntimeContext context;
    private final AuditLogService auditLog;
    private final Executor authExecutor;
    private final Logger logger;

    public PremiumCommand(
            AuthService authService,
            AccountRepository repository,
            RuntimeContext context,
            AuditLogService auditLog,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.repository = repository;
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
        authExecutor.execute(() -> {
            try {
                Optional<Account> opt = repository.findByUuid(player.getUniqueId());
                if (opt.isEmpty()) {
                    return;
                }
                Account account = opt.get();
                if (account.accountType() == AccountType.PREMIUM) {
                    player.sendMessage(Component.text(context.messages().raw("premium.already-premium")));
                    return;
                }
                if (account.accountType() == AccountType.PENDING_MIGRATION) {
                    player.sendMessage(Component.text(context.messages().raw("premium.already-requested")));
                    return;
                }
                repository.updateAccountType(account.id(), AccountType.PENDING_MIGRATION);
                auditLog.record("PREMIUM_REQUEST", account.usernameLower(), player.getUniqueId(), null, null);
                player.sendMessage(Component.text(context.messages().raw("premium.requested")));
            } catch (RuntimeException ex) {
                logger.error("/premium failed asynchronously for {}", player.getUsername(), ex);
                player.sendMessage(Component.text(context.messages().raw("error.internal")));
            }
        });
    }
}
