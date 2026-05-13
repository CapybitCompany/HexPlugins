package hexchat.service;

import hexchat.config.HexChatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class ChatFormatService {

    private final AtomicReference<HexChatConfig> configRef;
    private final AtomicBoolean invalidFormatWarned = new AtomicBoolean(false);
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Logger logger;

    public ChatFormatService(HexChatConfig initialConfig, Logger logger) {
        this.configRef = new AtomicReference<>(Objects.requireNonNull(initialConfig, "initialConfig"));
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void updateConfig(HexChatConfig newConfig) {
        this.configRef.set(Objects.requireNonNull(newConfig, "newConfig"));
        this.invalidFormatWarned.set(false);
    }

    public HexChatConfig currentConfig() {
        return configRef.get();
    }

    public boolean isChatEnabled() {
        return configRef.get().chat().enabled();
    }

    public Component render(Component sourceDisplayName, Component message) {
        HexChatConfig config = configRef.get();

        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("player", sourceDisplayName),
                Placeholder.component("message", message)
        );

        try {
            return miniMessage.deserialize(config.chat().format(), resolver);
        } catch (RuntimeException ex) {
            if (invalidFormatWarned.compareAndSet(false, true)) {
                logger.warning("Niepoprawny format czatu w config.yml ('chat.format'). Przełączam na format awaryjny.");
                logger.warning("Błąd parsera MiniMessage: " + ex.getMessage());
            }
            return sourceDisplayName.append(Component.text(": ")).append(message);
        }
    }
}
