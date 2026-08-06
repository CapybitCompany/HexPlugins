package hex.auctionbazaar.util;

import hex.auctionbazaar.config.MessagesConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Renders message paths into Components with placeholder replacement
 * and a configurable prefix.
 */
public final class MessageFactory {

    private final Supplier<MessagesConfig> messages;
    private final Supplier<String> prefix;

    public MessageFactory(Supplier<MessagesConfig> messages, Supplier<String> prefix) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    /** Czy klucz istnieje w załadowanych wiadomościach (np. do lokalizacji tokenów z bezpiecznym fallbackiem). */
    public boolean has(String path) {
        return messages.get().has(path);
    }

    public String raw(String path, Map<String, String> placeholders) {
        String template = messages.get().get(path);
        if (placeholders != null) {
            for (var e : placeholders.entrySet()) {
                template = LegacyFormat.replace(template, "<" + e.getKey() + ">", e.getValue());
            }
        }
        return template;
    }

    public Component render(String path, Map<String, String> placeholders) {
        String body = raw(path, placeholders);
        String fullPrefix = prefix.get() == null ? "" : prefix.get();
        return LegacyFormat.component(fullPrefix + body);
    }

    public Component renderNoPrefix(String path, Map<String, String> placeholders) {
        return LegacyFormat.component(raw(path, placeholders));
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, Map.of());
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        if (sender == null) return;
        sender.sendMessage(render(path, placeholders));
    }

    public static Map<String, String> placeholders(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("placeholder pairs must be even");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        }
        return out;
    }
}
