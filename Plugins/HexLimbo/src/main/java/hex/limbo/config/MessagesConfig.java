package hex.limbo.config;

import hex.limbo.text.LegacyText;
import net.kyori.adventure.text.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds chat messages keyed by message id. Lookups fall back to the key itself if missing.
 *
 * <p>{@link #raw(String)} and {@link #format(String, Object...)} return the configured string
 * verbatim (including any {@code &} colour codes) and stay the right choice for disconnect
 * reasons that are logged or compared. Anything shown to a player should go through
 * {@link #component(String, Object...)}, which runs the same string through the single
 * {@link LegacyText} parser so {@code &6}, {@code &l}, ... become real Adventure styling.
 */
public final class MessagesConfig {

    private final Map<String, String> messages;

    public MessagesConfig(Map<String, String> messages) {
        this.messages = new LinkedHashMap<>(Objects.requireNonNull(messages, "messages"));
    }

    public String raw(String key) {
        return messages.getOrDefault(key, key);
    }

    public String format(String key, Object... args) {
        String template = raw(key);
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    /**
     * Formats the message for the given key and parses its legacy colour codes into an Adventure
     * component. This is the only supported way to build player-facing text in HexLimbo; the
     * placeholder substitution is identical to {@link #format(String, Object...)}.
     */
    public Component component(String key, Object... args) {
        return LegacyText.parse(format(key, args));
    }

    public Map<String, String> asMap() {
        return Map.copyOf(messages);
    }
}
