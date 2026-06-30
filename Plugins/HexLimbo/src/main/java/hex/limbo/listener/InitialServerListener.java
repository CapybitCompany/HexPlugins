package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.limbo.LimboRouter;
import net.kyori.adventure.text.Component;

/**
 * Picks the initial backend server. Unauthenticated players go to the internal void limbo so they
 * can /login or /register; authenticated players (including admin-bypass users) go to the target
 * server. When the internal limbo is not ready, unauthenticated players are disconnected with
 * {@code disconnect.limbo-unavailable} instead of being dropped to a missing backend.
 */
public final class InitialServerListener {

    private final AuthService authService;
    private final LimboRouter router;
    private final RuntimeContext context;

    public InitialServerListener(AuthService authService, LimboRouter router, RuntimeContext context) {
        this.authService = authService;
        this.router = router;
        this.context = context;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        boolean authed = authService.isAuthenticated(player.getUniqueId())
                || player.hasPermission(context.config().adminBypassPermission());
        if (authed) {
            router.targetServer().ifPresent(event::setInitialServer);
            return;
        }
        if (!router.isLimboReady()) {
            player.disconnect(Component.text(context.messages().raw("disconnect.limbo-unavailable")));
            return;
        }
        router.limboServer().ifPresent(event::setInitialServer);
    }
}
