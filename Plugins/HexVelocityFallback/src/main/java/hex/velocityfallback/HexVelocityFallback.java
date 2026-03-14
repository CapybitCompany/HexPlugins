package hex.velocityfallback;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import hex.velocityfallback.listener.ServerKickListener;
import hex.velocityfallback.model.FallbackConfig;
import hex.velocityfallback.service.FallbackConfigService;
import hex.velocityfallback.service.FallbackRedirectService;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

@Plugin(
        id = "hexvelocityfallback",
        name = "HexVelocityFallback",
        version = "1.0.0",
        description = "Redirects players to fallback server when backend server shuts down or restarts.",
        authors = {"CapybitCompany"}
)
public final class HexVelocityFallback {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private FallbackRedirectService fallbackRedirectService;

    @Inject
    public HexVelocityFallback(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        FallbackConfigService configService = new FallbackConfigService(dataDirectory, logger);
        FallbackConfig fallbackConfig;
        try {
            fallbackConfig = configService.load();
        } catch (IOException ex) {
            logger.error("Could not load fallback configuration.", ex);
            return;
        }

        fallbackRedirectService = new FallbackRedirectService(proxyServer, logger);
        fallbackRedirectService.configure(fallbackConfig);

        proxyServer.getEventManager().register(this, new ServerKickListener(fallbackRedirectService));
        logger.info(
                "HexVelocityFallback enabled. Default fallback: '{}' ({}:{}), routes={}, connectFailure={}, emptyReason={}, keywords={}",
                fallbackConfig.defaultTarget().serverName(),
                fallbackConfig.defaultTarget().host(),
                fallbackConfig.defaultTarget().port(),
                fallbackConfig.sourceRoutes(),
                fallbackConfig.redirectOnConnectFailure(),
                fallbackConfig.redirectOnEmptyReason(),
                fallbackConfig.reasonKeywords()
        );
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (fallbackRedirectService != null) {
            fallbackRedirectService.shutdown();
        }
    }
}
