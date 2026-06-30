package hex.limbo.limbo;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Routes players to either the internal void limbo or the configured authenticated target server.
 *
 * <p>The limbo server name is read fresh from {@link RuntimeContext#config()}/{@code limbo} on
 * every call so reload picks up changes. The {@link LimboServer} reference is used to verify the
 * internal backend is actually accepting connections before we send a player at it; when it
 * isn't, the player is disconnected with {@code disconnect.limbo-unavailable} instead of being
 * routed into a hole.
 */
public final class LimboRouter {

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

    public void sendToLimbo(Player player) {
        if (!isLimboReady()) {
            logger.warn("Limbo backend not ready; kicking {} with disconnect.limbo-unavailable.", player.getUsername());
            player.disconnect(Component.text(context.messages().raw("disconnect.limbo-unavailable")));
            return;
        }
        RegisteredServer server = limboServer().orElseThrow();
        player.createConnectionRequest(server).fireAndForget();
    }

    public void sendToTarget(Player player) {
        Optional<RegisteredServer> server = targetServer();
        if (server.isEmpty()) {
            logger.warn("Target server '{}' is not registered in velocity.toml; cannot route player {}",
                    context.config().targetServer(), player.getUsername());
            return;
        }
        player.createConnectionRequest(server.get()).fireAndForget();
    }

    public boolean isLimbo(String serverName) {
        return serverName != null && serverName.equalsIgnoreCase(context.config().limboServer());
    }
}
