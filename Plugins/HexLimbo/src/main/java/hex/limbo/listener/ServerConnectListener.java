package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.limbo.LimboRouter;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/**
 * Stops unauthenticated players from connecting to anything other than the internal void limbo.
 * If a player tries to connect to a non-limbo backend while unauthenticated, we redirect to the
 * limbo – unless the limbo is unavailable, in which case the connection attempt is denied with
 * {@code disconnect.limbo-unavailable}.
 */
public final class ServerConnectListener {

    private final AuthService authService;
    private final LimboRouter router;
    private final RuntimeContext context;

    public ServerConnectListener(AuthService authService, LimboRouter router, RuntimeContext context) {
        this.authService = authService;
        this.router = router;
        this.context = context;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (event.getPlayer().hasPermission(context.config().adminBypassPermission())) {
            return;
        }
        if (authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            return;
        }
        Optional<RegisteredServer> originalTarget = event.getResult().getServer();
        if (originalTarget.isEmpty()) {
            return;
        }
        String targetName = originalTarget.get().getServerInfo().getName();
        if (router.isLimbo(targetName)) {
            return;
        }
        if (!router.isLimboReady()) {
            event.getPlayer().disconnect(Component.text(context.messages().raw("disconnect.limbo-unavailable")));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        event.getPlayer().sendMessage(Component.text(context.messages().raw("error.must-authenticate-first")));
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(router.limboServer().orElseThrow()));
    }
}
