package hex.velocityfallback.service;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import hex.velocityfallback.model.FallbackConfig;
import hex.velocityfallback.model.FallbackTargetConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FallbackRedirectService {

    private final ProxyServer proxyServer;
    private final Logger logger;

    private RegisteredServer defaultFallbackServer;
    private final Map<String, RegisteredServer> sourceRouteTargets = new HashMap<>();
    private final Set<ServerInfo> dynamicallyRegisteredServerInfos = new HashSet<>();
    private FallbackConfig config;

    public FallbackRedirectService(ProxyServer proxyServer, Logger logger) {
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    public void configure(FallbackConfig config) {
        this.config = config;
        defaultFallbackServer = resolveServer(config.defaultTarget(), "default");
        sourceRouteTargets.clear();

        for (Map.Entry<String, FallbackTargetConfig> entry : config.sourceRoutes().entrySet()) {
            String sourceServer = entry.getKey();
            RegisteredServer routeTarget = resolveServer(entry.getValue(), "route." + sourceServer);
            if (routeTarget != null) {
                sourceRouteTargets.put(sourceServer, routeTarget);
            }
        }
    }

    public void shutdown() {
        for (ServerInfo serverInfo : dynamicallyRegisteredServerInfos) {
            proxyServer.unregisterServer(serverInfo);
        }
        dynamicallyRegisteredServerInfos.clear();
        sourceRouteTargets.clear();
        defaultFallbackServer = null;
    }

    public void handleKickedFromServer(KickedFromServerEvent event) {
        String kickedFromName = event.getServer().getServerInfo().getName();
        RegisteredServer targetServer = resolveTargetForSource(kickedFromName);
        if (targetServer == null) {
            return;
        }

        String targetName = targetServer.getServerInfo().getName();
        if (kickedFromName.equalsIgnoreCase(targetName)) {
            return;
        }

        if (!shouldRedirect(event)) {
            return;
        }

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(targetServer));
        logger.debug(
                "Redirected player '{}' from '{}' to fallback '{}'.",
                event.getPlayer().getUsername(),
                kickedFromName,
                targetName
        );
    }

    private boolean shouldRedirect(KickedFromServerEvent event) {
        if (config == null) {
            return false;
        }

        if (config.redirectOnConnectFailure() && event.kickedDuringServerConnect()) {
            return true;
        }

        Optional<Component> kickReason = event.getServerKickReason();
        if (kickReason.isEmpty()) {
            return config.redirectOnEmptyReason();
        }

        String reasonText = extractPlainText(kickReason.get()).toLowerCase(Locale.ROOT).trim();
        if (reasonText.isEmpty()) {
            return config.redirectOnEmptyReason();
        }

        for (String keyword : config.reasonKeywords()) {
            if (reasonText.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private static String extractPlainText(Component component) {
        StringBuilder builder = new StringBuilder();
        appendComponentText(component, builder);
        return builder.toString();
    }

    private static void appendComponentText(Component component, StringBuilder builder) {
        if (component instanceof TextComponent textComponent) {
            builder.append(textComponent.content());
        }
        for (Component child : component.children()) {
            appendComponentText(child, builder);
        }
    }

    private RegisteredServer resolveTargetForSource(String sourceServerName) {
        if (sourceServerName == null || sourceServerName.isBlank()) {
            return defaultFallbackServer;
        }

        RegisteredServer sourceSpecific = sourceRouteTargets.get(sourceServerName.toLowerCase(Locale.ROOT));
        if (sourceSpecific != null) {
            return sourceSpecific;
        }

        return defaultFallbackServer;
    }

    private RegisteredServer resolveServer(FallbackTargetConfig target, String label) {
        Optional<RegisteredServer> existing = proxyServer.getServer(target.serverName());
        if (existing.isPresent()) {
            logger.info("Using already registered server '{}' for {}.", target.serverName(), label);
            return existing.get();
        }

        if (!target.hasAddress()) {
            logger.warn(
                    "Server '{}' for {} is not registered and has no host/port configured. Skipping this target.",
                    target.serverName(),
                    label
            );
            return null;
        }

        ServerInfo serverInfo = new ServerInfo(target.serverName(), target.address());
        proxyServer.registerServer(serverInfo);
        dynamicallyRegisteredServerInfos.add(serverInfo);

        RegisteredServer registered = proxyServer.getServer(target.serverName()).orElse(null);
        if (registered == null) {
            logger.warn("Could not register server '{}' for {}.", target.serverName(), label);
            return null;
        }

        logger.info(
                "Registered server '{}' for {} at {}:{}.",
                target.serverName(),
                label,
                target.host(),
                target.port()
        );
        return registered;
    }
}
