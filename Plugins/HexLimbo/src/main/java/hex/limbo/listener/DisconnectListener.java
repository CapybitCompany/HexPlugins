package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.prompt.PromptService;

/**
 * Ends the player's HexLimbo connection: auth state, login timeout, BossBar, chat reminder and any
 * queued lobby greeting all go away together. This also covers the login-timeout and admin
 * forcelogout/unregister paths, which all end in a disconnect.
 *
 * <p>The teardown is keyed on the concrete {@code Player} instance, never on the UUID alone.
 * {@code DisconnectEvent} can arrive after the same account has already reconnected - Velocity
 * gives no ordering guarantee between an old socket's disconnect and a new socket's login - and an
 * unconditional {@code remove(uuid)} would then wipe the live connection. {@link
 * ConnectionRegistry#endIfCurrent} removes the connection only when it is the one this event
 * belongs to; for a stale event it matches nothing and the newer connection is left untouched.
 *
 * <p>Ending the connection also retires its token, so a {@code /login} or {@code /register} whose
 * asynchronous password check is still running can no longer authenticate anybody, create a
 * session, write a success audit entry, route to the lobby or queue a greeting.
 */
public final class DisconnectListener {

    private final ConnectionRegistry connections;
    private final PromptService promptService;
    private final RouteCoordinator routes;

    public DisconnectListener(AuthService authService, PromptService promptService, RouteCoordinator routes) {
        this.connections = authService.connections();
        this.promptService = promptService;
        this.routes = routes;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        ConnectionHandle handle = connections.endIfCurrent(player.getUniqueId(), player).orElse(null);
        if (handle == null) {
            // A late event from a socket that has already been superseded. Its resources were
            // released when the replacement connection was opened; the live one must survive.
            return;
        }
        promptService.endConnection(handle);
        // Any pending transfer, retry timer or watchdog dies with the connection too.
        routes.endConnection(handle);
    }
}
