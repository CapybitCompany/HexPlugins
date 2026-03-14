package hex.velocityfallback.service;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import hex.velocityfallback.model.FallbackConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;

public final class FallbackRedirectService {

    private final ProxyServer proxyServer;
    private final Logger logger;

    private RegisteredServer fallbackServer;
    private ServerInfo dynamicallyRegisteredServerInfo;
    private FallbackConfig config;

    public FallbackRedirectService(ProxyServer proxyServer, Logger logger) {
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    public void configure(FallbackConfig config) {
        this.config = config;
        Optional<RegisteredServer> existing = proxyServer.getServer(config.serverName());
        if (existing.isPresent()) {
            fallbackServer = existing.get();
            logger.info("Using already registered fallback server '{}'.", config.serverName());
            return;
        }

        ServerInfo serverInfo = new ServerInfo(config.serverName(), config.address());
        proxyServer.registerServer(serverInfo);
        fallbackServer = proxyServer.getServer(config.serverName()).orElse(null);
        dynamicallyRegisteredServerInfo = serverInfo;

        logger.info(
                "Registered fallback server '{}' at {}:{}.",
                config.serverName(),
                config.host(),
                config.port()
        );
    }

    public void shutdown() {
        if (dynamicallyRegisteredServerInfo != null) {
            proxyServer.unregisterServer(dynamicallyRegisteredServerInfo);
            dynamicallyRegisteredServerInfo = null;
        }
        fallbackServer = null;
    }

    public void handleKickedFromServer(KickedFromServerEvent event) {
        if (fallbackServer == null) {
            return;
        }

        String fallbackName = fallbackServer.getServerInfo().getName();
        String kickedFromName = event.getServer().getServerInfo().getName();
        if (kickedFromName.equalsIgnoreCase(fallbackName)) {
            return;
        }

        if (!shouldRedirect(event)) {
            return;
        }

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(fallbackServer));
        logger.debug(
                "Redirected player '{}' from '{}' to fallback '{}'.",
                event.getPlayer().getUsername(),
                kickedFromName,
                fallbackName
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
}
