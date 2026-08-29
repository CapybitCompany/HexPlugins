package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.auth.RouteCoordinator;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * {@code /login}. Argument checking and message delivery only - the ordering of the password check,
 * the commit, the session row, the audit entry and the routing all live in {@link AuthFlow#login}.
 */
public final class LoginCommand implements SimpleCommand {

    private final AuthService authService;
    private final AuthFlow flow;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final Executor authExecutor;
    private final Logger logger;

    public LoginCommand(
            AuthService authService,
            AuthFlow flow,
            RouteCoordinator routes,
            RuntimeContext context,
            Executor authExecutor,
            Logger logger
    ) {
        this.authService = authService;
        this.flow = flow;
        this.routes = routes;
        this.context = context;
        this.authExecutor = authExecutor;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(context.messages().component("error.players-only"));
            return;
        }
        ConnectionHandle handle = FlowCommandSupport.resolve(authService, player);
        if (handle == null) {
            return;
        }
        Optional<AuthState> state = handle.authState();
        if (state.isEmpty()) {
            return;
        }
        MessagesConfig messages = context.messages();
        if (state.get().isAuthenticated()) {
            player.sendMessage(messages.component("login.already-authenticated"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(messages.component("login.usage"));
            return;
        }
        String password = args[0];
        FlowCommandSupport.runAsync("/login", authService, routes, handle, player, context,
                authExecutor, logger, () -> flow.login(handle, password));
    }
}
