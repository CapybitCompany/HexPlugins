package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.config.RuntimeContext;
import hex.limbo.limbo.LimboRouter;
import hex.limbo.prompt.PromptService;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/**
 * Stops unauthenticated players from connecting to anything other than the internal void limbo.
 * If a player tries to connect to a non-limbo backend while unauthenticated, we redirect to the
 * limbo – unless the limbo is unavailable, in which case the connection attempt is denied with
 * {@code disconnect.limbo-unavailable}. Once the player lands in the limbo it also greets them with
 * the correct prompt (register vs login).
 */
public final class ServerConnectListener {

    private final AuthService authService;
    private final LimboRouter router;
    private final RuntimeContext context;
    private final PromptService promptService;

    public ServerConnectListener(AuthService authService, LimboRouter router, RuntimeContext context, PromptService promptService) {
        this.authService = authService;
        this.router = router;
        this.context = context;
        this.promptService = promptService;
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

    /**
     * When an unauthenticated player lands in the limbo, show the correct prompt (register vs login)
     * as a BossBar, center-screen title and chat line via {@link PromptService}. Premium and
     * admin-bypass players skip the limbo entirely; when they arrive in the lobby they instead get a
     * short "limbo skipped" greeting. Authenticated cracked players who land in the lobby after a
     * transfer already received their success message and are left alone.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        boolean adminBypass = player.hasPermission(context.config().adminBypassPermission());

        if (router.isLimbo(serverName)) {
            if (adminBypass) {
                return;
            }
            AuthState state = authService.stateOf(player.getUniqueId()).orElse(null);
            if (state == null || state.isAuthenticated()) {
                return;
            }
            promptService.showLimboPrompt(player.getUniqueId(), player, state.stage());
            return;
        }

        if (serverName.equalsIgnoreCase(context.config().targetServer())) {
            AuthState state = authService.stateOf(player.getUniqueId()).orElse(null);
            boolean premium = state != null && state.stage() == AuthState.Stage.AUTHENTICATED_PREMIUM;
            if (adminBypass || premium) {
                promptService.showPremiumSkip(player);
            }
        }
    }
}
