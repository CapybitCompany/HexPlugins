package hexchat.service;

import hexchat.config.HexChatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class HexChatMessageService {

    private final Supplier<HexChatConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Logger logger;
    private final Set<String> warnedMessageKeys = ConcurrentHashMap.newKeySet();

    public HexChatMessageService(Supplier<HexChatConfig> configSupplier, Logger logger) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void sendNoPermission(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().noPermission(), "messages.no-permission");
    }

    public void sendReloaded(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().reloaded(), "messages.reloaded");
    }

    public void sendUsage(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().usage(), "messages.usage");
    }

    public void sendCooldownWait(CommandSender sender, long secondsLeft) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().cooldownWait(),
                "messages.cooldown-wait",
                Placeholder.unparsed("seconds", String.valueOf(Math.max(1, secondsLeft)))
        );
    }

    public void sendChatMuted(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().chatMuted(), "messages.chat-muted");
    }

    public void sendChatMuteEnabled(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().chatMuteEnabled(), "messages.chat-mute-enabled");
    }

    public void sendChatMuteDisabled(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().chatMuteDisabled(), "messages.chat-mute-disabled");
    }

    public void sendChatMuteAlreadyEnabled(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().chatMuteAlreadyEnabled(), "messages.chat-mute-already-enabled");
    }

    public void sendChatMuteAlreadyDisabled(CommandSender sender) {
        sendPrefixed(sender, configSupplier.get().messages().chatMuteAlreadyDisabled(), "messages.chat-mute-already-disabled");
    }

    public void sendChatMuteStatus(CommandSender sender, boolean muted) {
        if (muted) {
            sendPrefixed(sender, configSupplier.get().messages().chatMuteStatusEnabled(), "messages.chat-mute-status-enabled");
            return;
        }
        sendPrefixed(sender, configSupplier.get().messages().chatMuteStatusDisabled(), "messages.chat-mute-status-disabled");
    }

    public void sendCommandBlocked(CommandSender sender, String message) {
        sendPrefixed(sender, message, "command-filter.block-message");
    }

    public void sendContentBlocked(CommandSender sender, String message) {
        sendPrefixed(sender, message, "content-filter.block-message");
    }

    public void sendPrivateMuted(CommandSender sender, String timeText, String reason) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().privateMuted(),
                "messages.private-muted",
                TagResolver.resolver(
                        Placeholder.unparsed("time", timeText),
                        Placeholder.unparsed("reason", reason)
                )
        );
    }

    /**
     * Natychmiastowe powiadomienie wyciszanego gracza, gdy jest online.
     * Osobny tekst od {@code messages.private-muted}, który dostaje gracz przy próbie pisania.
     * Nazwa gracza, czas i powód idą przez {@link Placeholder#unparsed}, więc treści od
     * użytkowników nie mogą wstrzyknąć własnych tagów MiniMessage.
     */
    public void sendPlayerMuteNotification(CommandSender sender, String playerName, String timeText, String reason) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteNotification(),
                "messages.player-mute-notification",
                TagResolver.resolver(
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("time", timeText),
                        Placeholder.unparsed("reason", reason)
                )
        );
    }

    public void sendPlayerMuteSet(CommandSender sender, String playerName, String timeText, String reason) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteSet(),
                "messages.player-mute-set",
                TagResolver.resolver(
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("time", timeText),
                        Placeholder.unparsed("reason", reason)
                )
        );
    }

    public void sendPlayerMuteRemoved(CommandSender sender, String playerName) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteRemoved(),
                "messages.player-mute-removed",
                Placeholder.unparsed("player", playerName)
        );
    }

    public void sendPlayerMuteNotMuted(CommandSender sender, String playerName) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteNotMuted(),
                "messages.player-mute-not-muted",
                Placeholder.unparsed("player", playerName)
        );
    }

    public void sendPlayerMuteTargetNotFound(CommandSender sender, String playerName) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteTargetNotFound(),
                "messages.player-mute-target-not-found",
                Placeholder.unparsed("player", playerName)
        );
    }

    public void sendPlayerMuteInfo(CommandSender sender, String playerName, String timeText, String reason) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteInfo(),
                "messages.player-mute-info",
                TagResolver.resolver(
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("time", timeText),
                        Placeholder.unparsed("reason", reason)
                )
        );
    }

    public void sendPlayerMuteDurationInvalid(CommandSender sender, String input) {
        sendPrefixed(
                sender,
                configSupplier.get().messages().playerMuteDurationInvalid(),
                "messages.player-mute-duration-invalid",
                Placeholder.unparsed("input", input)
        );
    }

    public void sendHelpUnavailable(CommandSender sender, String body) {
        sendPrefixed(sender, body, "help.unavailable-message");
    }

    public void sendRawWithoutPrefix(CommandSender sender, String body, String messageKey) {
        send(sender, body, false, messageKey);
    }

    public void sendRawWithoutPrefix(CommandSender sender, String body, String messageKey, TagResolver resolver) {
        send(sender, body, false, messageKey, resolver);
    }

    public void sendRawLinesWithoutPrefix(CommandSender sender, List<String> lines, String messageKey) {
        for (String line : lines) {
            send(sender, line, false, messageKey);
        }
    }

    private void sendPrefixed(CommandSender sender, String body, String messageKey) {
        send(sender, body, true, messageKey);
    }

    private void sendPrefixed(CommandSender sender, String body, String messageKey, TagResolver resolver) {
        send(sender, body, true, messageKey, resolver);
    }

    private void send(CommandSender sender, String body, boolean prefixed, String messageKey, TagResolver... resolvers) {
        HexChatConfig.Messages messages = configSupplier.get().messages();
        String parsedMessage = prefixed ? messages.prefix() + body : body;

        try {
            Component component;
            if (resolvers == null || resolvers.length == 0) {
                component = miniMessage.deserialize(parsedMessage);
            } else {
                component = miniMessage.deserialize(parsedMessage, TagResolver.resolver(resolvers));
            }
            sender.sendMessage(component);
        } catch (RuntimeException ex) {
            if (warnedMessageKeys.add(messageKey)) {
                logger.warning("Niepoprawny MiniMessage w '" + messageKey + "'. Używam surowego tekstu.");
                logger.warning("Błąd parsera MiniMessage: " + ex.getMessage());
            }
            sender.sendMessage(Component.text(stripTags(parsedMessage)));
        }
    }

    private String stripTags(String input) {
        return input.replaceAll("<[^>]+>", "");
    }
}
