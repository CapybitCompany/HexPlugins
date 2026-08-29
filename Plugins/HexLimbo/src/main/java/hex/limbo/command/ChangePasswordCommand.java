package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.config.RuntimeContext;
import hex.limbo.auth.RouteCoordinator;
import org.slf4j.Logger;

import java.util.concurrent.Executor;

/**
 * {@code /changepassword}. The hash and the DB write run on the auth executor, so the connection can
 * go stale mid-call; {@link AuthFlow#changePassword} takes the same commit lease as the other
 * persistent auth writes, which is what stops a departed player's password from being rotated.
 */
public final class ChangePasswordCommand implements SimpleCommand {

    private final AuthService authService;
    private final AuthFlow flow;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final Executor authExecutor;
    private final Logger logger;

    public ChangePasswordCommand(
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
        if (handle == null || !handle.isAuthenticated()) {
            player.sendMessage(context.messages().component("error.must-authenticate-first"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(context.messages().component("changepassword.usage"));
            return;
        }
        String oldPw = args[0];
        String newPw = args[1];
        FlowCommandSupport.runAsync("/changepassword", authService, routes, handle, player, context,
                authExecutor, logger, () -> flow.changePassword(handle, oldPw, newPw));
    }
}
