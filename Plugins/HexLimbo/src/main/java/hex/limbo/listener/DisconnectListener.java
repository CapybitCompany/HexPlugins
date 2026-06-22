package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import hex.limbo.auth.AuthService;

/**
 * Cleans up per-connection auth state and cancels the login timeout when the player leaves.
 */
public final class DisconnectListener {

    private final AuthService authService;
    private final LoginListener loginListener;

    public DisconnectListener(AuthService authService, LoginListener loginListener) {
        this.authService = authService;
        this.loginListener = loginListener;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        loginListener.cancelLoginTimer(uuid);
        authService.removeConnection(uuid);
    }
}
