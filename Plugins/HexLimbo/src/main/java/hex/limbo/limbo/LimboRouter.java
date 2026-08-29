package hex.limbo.limbo;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.config.RuntimeContext;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Routes players to either the internal void limbo or the configured authenticated target server.
 *
 * <p>The limbo server name is read fresh from {@link RuntimeContext#config()}/{@code limbo} on
 * every call so reload picks up changes. The {@link LimboServer} reference is used to verify the
 * internal backend is actually accepting connections before we send a player at it; when it
 * isn't, the player is disconnected with {@code disconnect.limbo-unavailable} instead of being
 * routed into a hole.
 *
 * <p>Nothing here decides <em>whether</em> a player should be moved, or in what order - that is
 * {@link RouteCoordinator}'s job, and this class is the {@link RouteCoordinator.Transport} it
 * drives. Callers therefore go through the coordinator, never straight to a connection request.
 */
public final class LimboRouter implements RouteCoordinator.Transport {

    private final ProxyServer proxy;
    private final RuntimeContext context;
    private final LimboServer limboServerHandle;
    private final Logger logger;

    public LimboRouter(ProxyServer proxy, RuntimeContext context, LimboServer limboServerHandle, Logger logger) {
        this.proxy = proxy;
        this.context = context;
        this.limboServerHandle = limboServerHandle;
        this.logger = logger;
    }

    public Optional<RegisteredServer> limboServer() {
        return proxy.getServer(context.config().limboServer());
    }

    public Optional<RegisteredServer> targetServer() {
        return proxy.getServer(context.config().targetServer());
    }

    public boolean isLimboReady() {
        return limboServerHandle.isReady() && limboServer().isPresent();
    }

    /**
     * Issues one transfer and reports how it ended.
     *
     * <p>{@code connect()} rather than {@code fireAndForget()}: the outcome is what
     * {@link RouteCoordinator} needs in order to tell "the player is there now" from "Velocity
     * refused because another transfer was running". Firing and forgetting throws that away, and
     * with it the only chance to notice that an older transfer won.
     *
     * <p>Returns immediately - the caller is inside a commit section and must not block.
     */
    @Override
    public CompletionStage<RouteCoordinator.TransferStatus> send(
            ConnectionHandle handle, Object connection, RouteCoordinator.Destination destination) {
        if (!(connection instanceof Player player)) {
            return CompletableFuture.completedFuture(RouteCoordinator.TransferStatus.UNAVAILABLE);
        }
        if (destination == RouteCoordinator.Destination.LIMBO && !isLimboReady()) {
            logger.warn("Limbo backend not ready; kicking {} with disconnect.limbo-unavailable.", player.getUsername());
            player.disconnect(context.messages().component("disconnect.limbo-unavailable"));
            return CompletableFuture.completedFuture(RouteCoordinator.TransferStatus.UNAVAILABLE);
        }
        Optional<RegisteredServer> server = destination == RouteCoordinator.Destination.LIMBO
                ? limboServer()
                : targetServer();
        if (server.isEmpty()) {
            logger.warn("Server for {} is not registered in velocity.toml; cannot route player {}",
                    destination, player.getUsername());
            return CompletableFuture.completedFuture(RouteCoordinator.TransferStatus.UNAVAILABLE);
        }
        return player.createConnectionRequest(server.get()).connect()
                .thenApply(result -> translate(result.getStatus()));
    }

    /** The fail-closed exit: close a connection that cannot be put where it belongs. */
    @Override
    public void disconnect(ConnectionHandle handle, Object connection, String messageKey) {
        if (connection instanceof Player player) {
            player.disconnect(context.messages().component(messageKey));
        }
    }

    private static RouteCoordinator.TransferStatus translate(ConnectionRequestBuilder.Status status) {
        return switch (status) {
            case SUCCESS -> RouteCoordinator.TransferStatus.SUCCESS;
            case ALREADY_CONNECTED -> RouteCoordinator.TransferStatus.ALREADY_CONNECTED;
            case CONNECTION_IN_PROGRESS -> RouteCoordinator.TransferStatus.CONNECTION_IN_PROGRESS;
            case CONNECTION_CANCELLED -> RouteCoordinator.TransferStatus.CONNECTION_CANCELLED;
            case SERVER_DISCONNECTED -> RouteCoordinator.TransferStatus.SERVER_DISCONNECTED;
        };
    }

    public boolean isLimbo(String serverName) {
        return serverName != null && serverName.equalsIgnoreCase(context.config().limboServer());
    }
}
