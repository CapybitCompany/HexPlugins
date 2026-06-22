package hex.limbo.limbo;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import hex.limbo.config.RuntimeContext;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Routes a player to either the limbo server or the configured authenticated target server.
 * Server names are read fresh from {@link RuntimeContext} on every call so {@code /hexlimbo reload}
 * picks up new values without restarting.
 */
public final class LimboRouter {

    private final ProxyServer proxy;
    private final RuntimeContext context;
    private final Logger logger;

    public LimboRouter(ProxyServer proxy, RuntimeContext context, Logger logger) {
        this.proxy = proxy;
        this.context = context;
        this.logger = logger;
    }

    public Optional<RegisteredServer> limboServer() {
        return proxy.getServer(context.config().limboServer());
    }

    public Optional<RegisteredServer> targetServer() {
        return proxy.getServer(context.config().targetServer());
    }

    public void sendToLimbo(Player player) {
        Optional<RegisteredServer> server = limboServer();
        if (server.isEmpty()) {
            logger.warn("Limbo server '{}' is not registered in velocity.toml; cannot route player {}",
                    context.config().limboServer(), player.getUsername());
            return;
        }
        player.createConnectionRequest(server.get()).fireAndForget();
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
