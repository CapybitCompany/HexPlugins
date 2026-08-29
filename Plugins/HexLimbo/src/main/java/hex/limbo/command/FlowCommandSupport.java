package hex.limbo.command;

import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.FlowResultApplier;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.config.RuntimeContext;
import org.slf4j.Logger;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The plumbing every player command shares: resolve the caller's connection handle, run the flow on
 * the auth executor, and apply what it decided.
 *
 * <p>The handle is always resolved on the command thread, i.e. while this exact {@code Player} is
 * provably the connection that owns the UUID. Passing that handle into the worker is what lets the
 * flow tell "still here" from "left or reconnected" once the slow part is done.
 *
 * <p>Every command goes through {@link #runAsync}, so there is exactly one production answer to
 * "what happens when an asynchronous flow throws" rather than five subtly different catch blocks.
 * The decisions - whether a result may still be applied, whether a failure may still be reported -
 * all live in {@link FlowResultApplier}; this class only supplies the Velocity side of the effects.
 */
final class FlowCommandSupport {

    private FlowCommandSupport() {}

    /** The connection this player owns right now, or {@code null} if HexLimbo does not track them. */
    static ConnectionHandle resolve(AuthService authService, Player player) {
        return authService.connections().currentFor(player.getUniqueId(), player).orElse(null);
    }

    /**
     * Runs {@code flow} on the auth executor and applies its result in the connection's operation
     * order. A result overtaken by a newer operation, or belonging to a socket that has gone, is
     * dropped in full; a flow that throws is logged and its {@code error.internal} line is ordered
     * the same way.
     */
    static void runAsync(
            String label,
            AuthService authService,
            RouteCoordinator routes,
            ConnectionHandle handle,
            Player player,
            RuntimeContext context,
            Executor authExecutor,
            Logger logger,
            Supplier<AuthFlow.Result> flow
    ) {
        authExecutor.execute(() -> FlowResultApplier.execute(
                authService.connections(), handle, player,
                effects(routes, handle, player, context), label, logger, flow));
    }

    /**
     * Applies a result the caller already has - the staff force-logout path, which needs the
     * outcome in order to report it truthfully.
     */
    static FlowResultApplier.Application apply(
            AuthFlow.Result result,
            AuthService authService,
            RouteCoordinator routes,
            ConnectionHandle handle,
            Player player,
            RuntimeContext context
    ) {
        return FlowResultApplier.apply(authService.connections(), result, player,
                effects(routes, handle, player, context));
    }

    /**
     * The Velocity binding. Routing goes through {@link RouteCoordinator} rather than straight at a
     * connection request, so a transfer that is still running cannot make a newer decision lose.
     */
    private static FlowResultApplier.Effects effects(
            RouteCoordinator routes, ConnectionHandle handle, Player player, RuntimeContext context) {
        return new FlowResultApplier.Effects() {
            @Override
            public void sendMessage(String messageKey, Object[] args) {
                player.sendMessage(context.messages().component(messageKey, args));
            }

            @Override
            public void disconnect(String messageKey, Object[] args) {
                player.disconnect(context.messages().component(messageKey, args));
            }

            @Override
            public CompletionStage<RouteCoordinator.RouteResult> sendToTarget() {
                return routes.route(handle, player, RouteCoordinator.Destination.TARGET);
            }

            @Override
            public CompletionStage<RouteCoordinator.RouteResult> sendToLimbo() {
                return routes.route(handle, player, RouteCoordinator.Destination.LIMBO);
            }
        };
    }
}
