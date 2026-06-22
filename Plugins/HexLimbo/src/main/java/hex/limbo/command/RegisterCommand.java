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
import hex.limbo.premium.PremiumResolver;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * /register dispatches its DB + BCrypt work to the auth executor so the netty command dispatcher
 * is never blocked.
 */
public final class RegisterCommand implements SimpleCommand {

    private final AuthService authService;
    private final SessionService sessionService;
    private final LimboRouter router;
    private final RuntimeContext context;
    private final PremiumResolver premiumResolver;
    private final AuditLogService auditLog;
    private final Executor authExecutor;
    private final Logger logger;

    public RegisterCommand(
            AuthService authService,
            SessionService sessionService,
            LimboRouter router,
            RuntimeContext context,
            PremiumResolver premiumResolver,
            AuditLogService auditLog,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.router = router;
        this.context = context;
        this.premiumResolver = premiumResolver;
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
        Optional<AuthState> state = authService.stateOf(player.getUniqueId());
        if (state.isEmpty()) {
            return;
        }
        MessagesConfig messages = context.messages();
        if (state.get().isAuthenticated()) {
            player.sendMessage(Component.text(messages.raw("register.already-authenticated")));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(Component.text(messages.raw("register.usage")));
            return;
        }
        String password = args[0];
        String confirm = args[1];

        authExecutor.execute(() -> {
            try {
                runRegister(player, state.get(), password, confirm);
            } catch (RuntimeException ex) {
                logger.error("/register failed asynchronously for {}", player.getUsername(), ex);
                player.sendMessage(Component.text(context.messages().raw("error.internal")));
            }
        });
    }

    private void runRegister(Player player, AuthState state, String password, String confirm) {
        MessagesConfig messages = context.messages();
        boolean nameIsPremium;
        if (!context.config().premium().enabled()) {
            // Offline-only mode: never call Mojang from /register. Treat every name as cracked.
            nameIsPremium = false;
        } else {
            PremiumResolver.Result premium = premiumResolver.resolve(player.getUsername());
            if (premium.isUnknown() && !context.config().premium().failOpenOnCheckError()) {
                player.sendMessage(Component.text(messages.raw("register.premium-check-unavailable")));
                return;
            }
            nameIsPremium = premium.isPremium();
        }
        AuthService.RegisterOutcome outcome = authService.attemptRegister(
                player.getUniqueId(), password, confirm, nameIsPremium);
        switch (outcome) {
            case SUCCESS -> {
                player.sendMessage(Component.text(messages.raw("register.success")));
                state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);
                authService.repository().findByUuid(player.getUniqueId()).ifPresent(account ->
                        sessionService.createSession(account.id(), player.getUniqueId(), account.usernameLower(), state.ipHash()));
                auditLog.record("REGISTER", player.getUsername().toLowerCase(), player.getUniqueId(), state.ipHash(), null);
                router.sendToTarget(player);
            }
            case PASSWORD_MISMATCH -> player.sendMessage(Component.text(messages.raw("register.password-mismatch")));
            case PASSWORD_TOO_SHORT -> player.sendMessage(Component.text(messages.format("register.password-too-short", context.config().security().minPasswordLength())));
            case ALREADY_REGISTERED -> player.sendMessage(Component.text(messages.raw("register.already-registered")));
            case TOO_MANY_ACCOUNTS_FOR_IP -> player.sendMessage(Component.text(messages.raw("register.too-many-accounts")));
            case RATE_LIMITED -> player.sendMessage(Component.text(messages.raw("error.rate-limited")));
            case PREMIUM_NAME_PROTECTED -> player.sendMessage(Component.text(messages.raw("register.premium-name-protected")));
        }
    }
}
