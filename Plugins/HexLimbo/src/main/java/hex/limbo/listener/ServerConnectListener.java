package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.config.RuntimeContext;
import hex.limbo.limbo.LimboRouter;
import hex.limbo.prompt.PromptService;

import java.util.Optional;
import java.util.UUID;

/**
 * Stops unauthenticated players from connecting to anything other than the internal void limbo.
 * If a player tries to connect to a non-limbo backend while unauthenticated, we redirect to the
 * limbo – unless the limbo is unavailable, in which case the connection attempt is denied with
 * {@code disconnect.limbo-unavailable}. Once the player lands in the limbo it also greets them with
 * the correct prompt (register vs login).
 */
public final class ServerConnectListener {

    /** What the pre-connect gate has decided about one connection attempt. */
    public enum PreConnectDecision {
        /** Let the attempt through unchanged. */
        ALLOW,
        /** Redirect to the limbo and tell the player why. */
        REDIRECT_TO_LIMBO,
        /** The limbo cannot take them and they may not go anywhere else: disconnect. */
        DENY_LIMBO_UNAVAILABLE
    }

    private final AuthService authService;
    private final LimboRouter router;
    private final RouteCoordinator routes;
    private final RuntimeContext context;
    private final PromptService promptService;

    public ServerConnectListener(
            AuthService authService,
            LimboRouter router,
            RouteCoordinator routes,
            RuntimeContext context,
            PromptService promptService
    ) {
        this.authService = authService;
        this.router = router;
        this.routes = routes;
        this.context = context;
        this.promptService = promptService;
    }

    /**
     * The pre-connect gate itself, expressed as a decision rather than as a mutation of a Velocity
     * event so it can be exercised directly.
     *
     * <p><b>The authentication question is asked about the connection, never about the UUID.</b>
     * {@link ConnectionRegistry#isAuthenticatedConnection} answers {@code true} only when
     * {@code connection} is the exact socket currently registered for {@code uuid} <em>and</em> that
     * socket is authenticated. Asking {@code isAuthenticated(uuid)} instead would let a superseded
     * socket inherit the auth state of the reconnect that displaced it: connection A is
     * unauthenticated, B takes the UUID over and logs in, and a {@code ServerPreConnectEvent} that
     * was already in flight for A would then be waved through to the target server.
     *
     * <p>Every other case is fail-closed - no handle at all, a foreign {@code Player} instance, a
     * displaced connection - because none of them proves this socket authenticated.
     *
     * @param connection      the concrete {@code Player} the event carries, compared by identity
     * @param adminBypass     the caller's already-evaluated permission check, unchanged in meaning
     * @param targetIsLimbo   whether the attempt is already headed for the limbo
     * @param limboReady      whether the limbo backend can currently take connections
     */
    public PreConnectDecision decidePreConnect(
            UUID uuid, Object connection, boolean adminBypass, boolean targetIsLimbo, boolean limboReady) {
        if (adminBypass) {
            return PreConnectDecision.ALLOW;
        }
        if (authService.connections().isAuthenticatedConnection(uuid, connection)) {
            return PreConnectDecision.ALLOW;
        }
        if (targetIsLimbo) {
            return PreConnectDecision.ALLOW;
        }
        return limboReady ? PreConnectDecision.REDIRECT_TO_LIMBO : PreConnectDecision.DENY_LIMBO_UNAVAILABLE;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        boolean adminBypass = player.hasPermission(context.config().adminBypassPermission());
        Optional<RegisteredServer> originalTarget = event.getResult().getServer();
        if (originalTarget.isEmpty()) {
            // Somebody already cancelled the attempt; there is no destination left to gate.
            return;
        }
        String targetName = originalTarget.get().getServerInfo().getName();
        PreConnectDecision decision = decidePreConnect(
                player.getUniqueId(), player, adminBypass, router.isLimbo(targetName), router.isLimboReady());
        switch (decision) {
            case ALLOW -> {
                // nothing to do
            }
            case DENY_LIMBO_UNAVAILABLE -> {
                player.disconnect(context.messages().component("disconnect.limbo-unavailable"));
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
            case REDIRECT_TO_LIMBO -> {
                player.sendMessage(context.messages().component("error.must-authenticate-first"));
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(router.limboServer().orElseThrow()));
            }
        }
    }

    /**
     * The only place that is allowed to put authentication text on a player's screen, because it is
     * the only event that proves where the player actually is.
     *
     * <ul>
     *     <li><b>Arrived in the limbo, unauthenticated:</b> show the login-or-register prompt
     *     (BossBar + one-shot title + chat). {@link PromptService#showLimboPrompt} is idempotent per
     *     connection, so a repeated event cannot stack a second BossBar or reminder.</li>
     *     <li><b>Arrived on the configured target server:</b> release whichever greeting the auth
     *     pipeline queued – manual {@code /login}, {@code /register}, session auto-login, premium
     *     handshake or admin bypass. The pending entry is consumed atomically, so the title shows
     *     exactly once per successful authentication and never on a later lobby return.</li>
     * </ul>
     *
     * <p>Both branches start by resolving the event's {@code Player} to the connection that
     * currently owns it. An event that arrives after the player disconnected - or after the same
     * UUID was taken over by a reconnect - resolves to nothing and is dropped, so it can neither
     * create a BossBar and reminder task for a dead socket nor consume the live connection's
     * greeting.
     *
     * <p>It is also the only event that can confirm where a connection request actually ended up,
     * so it is where {@link RouteCoordinator#onArrived} gets to repair a wrong end state - a wish
     * that lost a {@code CONNECTION_IN_PROGRESS} race, or an unauthenticated player who landed on a
     * real backend because an older transfer won. The greeting is released only for an arrival the
     * coordinator accepts, so a player who is about to be bounced back is never congratulated.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        ConnectionHandle handle = authService.connections()
                .currentFor(player.getUniqueId(), player).orElse(null);
        if (handle == null) {
            return;
        }

        handleArrival(handle, player,
                router.isLimbo(serverName),
                serverName.equalsIgnoreCase(context.config().targetServer()),
                player.hasPermission(context.config().adminBypassPermission()));
    }

    /**
     * The body of {@code onServerConnected}, minus the Velocity event, so the arrival rules can be
     * exercised directly.
     *
     * <p>Order matters here. The coordinator is told where the player landed <em>first</em>, because
     * it is the only thing that can tell a correct arrival from one an older transfer produced. Only
     * an arrival it accepts releases the queued greeting: a player who is about to be bounced back
     * to the limbo must not first be congratulated on reaching the target.
     */
    public void handleArrival(
            ConnectionHandle handle, Object connection, boolean isLimbo, boolean isTarget, boolean adminBypass) {
        RouteCoordinator.Destination arrived = isLimbo
                ? RouteCoordinator.Destination.LIMBO
                : isTarget ? RouteCoordinator.Destination.TARGET : null;

        // Staff with the bypass permission are routed by hand and are never corrected; everyone
        // else has their arrival checked against the newest routing decision.
        boolean accepted = adminBypass || routes.onArrived(handle, connection, arrived);

        if (isLimbo) {
            if (adminBypass) {
                return;
            }
            AuthState state = handle.authState().orElse(null);
            if (state == null || state.isAuthenticated()) {
                // An authenticated player in the limbo is legitimate - /limbo, or a target that is
                // still being requested - so no prompt and no forced move on auth state alone.
                return;
            }
            promptService.showLimboPrompt(handle, state.stage());
            return;
        }

        if (isTarget && accepted) {
            promptService.onArrivedAtTarget(handle);
        }
    }
}
