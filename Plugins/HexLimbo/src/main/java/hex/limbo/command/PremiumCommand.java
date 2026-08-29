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
 * {@code /premium}. The account lookup and the type change run on the auth executor, so
 * {@link AuthFlow#requestPremiumMigration} guards the write with the same commit lease the auth
 * writes use: a player who disconnected during the lookup does not get their account type changed.
 */
public final class PremiumCommand implements SimpleCommand {

    private final AuthService authService;
    private final AuthFlow flow;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final Executor authExecutor;
    private final Logger logger;

    public PremiumCommand(
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
        FlowCommandSupport.runAsync("/premium", authService, routes, handle, player, context,
                authExecutor, logger, () -> flow.requestPremiumMigration(handle));
    }
}
