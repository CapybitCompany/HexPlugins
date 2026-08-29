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
 * {@code /register}. Dispatches the premium check, BCrypt hashing and DB write to the auth executor
 * so the netty command dispatcher is never blocked; the ordering lives in {@link AuthFlow#register}.
 */
public final class RegisterCommand implements SimpleCommand {

    private final AuthService authService;
    private final AuthFlow flow;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final Executor authExecutor;
    private final Logger logger;

    public RegisterCommand(
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
            player.sendMessage(messages.component("register.already-authenticated"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(messages.component("register.usage"));
            return;
        }
        String password = args[0];
        String confirm = args[1];
        FlowCommandSupport.runAsync("/register", authService, routes, handle, player, context,
                authExecutor, logger, () -> flow.register(handle, password, confirm));
    }
}
