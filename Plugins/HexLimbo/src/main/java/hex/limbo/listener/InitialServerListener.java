package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import hex.limbo.limbo.LimboRouter;

/**
 * Picks the initial backend server. Unauthenticated players are forced to the limbo server so they
 * can /login or /register; authenticated players (including admin-bypass users) go to the
 * configured target server.
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
        boolean authed = authService.isAuthenticated(event.getPlayer().getUniqueId())
                || event.getPlayer().hasPermission(context.config().adminBypassPermission());
        if (authed) {
            router.targetServer().ifPresent(event::setInitialServer);
        } else {
            router.limboServer().ifPresent(event::setInitialServer);
        }
    }
}
