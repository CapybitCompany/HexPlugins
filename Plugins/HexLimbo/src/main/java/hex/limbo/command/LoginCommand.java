package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.SessionService;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.limbo.LimboRouter;
import hex.limbo.prompt.PromptService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.Executor;

public final class LoginCommand implements SimpleCommand {

    private final AuthService authService;
    private final SessionService sessionService;
    private final LimboRouter router;
    private final RuntimeContext context;
    private final AuditLogService auditLog;
    private final PromptService promptService;
    private final Executor authExecutor;
    private final Logger logger;

    public LoginCommand(
            AuthService authService,
            SessionService sessionService,
            LimboRouter router,
            RuntimeContext context,
            AuditLogService auditLog,
            PromptService promptService,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.router = router;
        this.context = context;
        this.auditLog = auditLog;
        this.promptService = promptService;
        this.authExecutor = authExecutor;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text(context.messages().raw("error.players-only")));
            return;
        }
        Optional<AuthState> state = authService.stateOf(player.getUniqueId());
        if (state.isEmpty()) {
            return;
        }
        MessagesConfig messages = context.messages();
        if (state.get().isAuthenticated()) {
            player.sendMessage(Component.text(messages.raw("login.already-authenticated")));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(Component.text(messages.raw("login.usage")));
            return;
        }
        String password = args[0];
        authExecutor.execute(() -> {
            try {
                runLogin(player, state.get(), password);
            } catch (RuntimeException ex) {
                logger.error("/login failed asynchronously for {}", player.getUsername(), ex);
                player.sendMessage(Component.text(context.messages().raw("error.internal")));
            }
        });
    }

    private void runLogin(Player player, AuthState state, String password) {
        MessagesConfig messages = context.messages();
        AuthService.LoginOutcome outcome = authService.attemptLogin(player.getUniqueId(), password);
        switch (outcome) {
            case SUCCESS -> {
                player.sendMessage(Component.text(messages.raw("login.success")));
                promptService.onAuthenticated(player.getUniqueId(), player);
                authService.repository().findByUsername(player.getUsername()).ifPresent(account ->
                        sessionService.createSession(account.id(), player.getUniqueId(), account.usernameLower(), state.ipHash()));
                auditLog.record("LOGIN", player.getUsername().toLowerCase(), player.getUniqueId(), state.ipHash(), null);
                router.sendToTarget(player);
            }
            case WRONG_PASSWORD -> {
                player.sendMessage(Component.text(messages.raw("login.wrong-password")));
                auditLog.record("LOGIN_FAIL", player.getUsername().toLowerCase(), player.getUniqueId(), state.ipHash(), "wrong-password");
            }
            case ACCOUNT_LOCKED -> player.sendMessage(Component.text(messages.raw("login.account-locked")));
            case ACCOUNT_NOT_FOUND -> player.sendMessage(Component.text(messages.raw("login.not-registered")));
            case RATE_LIMITED -> player.sendMessage(Component.text(messages.raw("error.rate-limited")));
        }
    }
}
