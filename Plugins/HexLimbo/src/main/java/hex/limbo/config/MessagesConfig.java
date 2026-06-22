package hex.limbo.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds chat messages keyed by message id. Lookups fall back to the key itself if missing.
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

    public Map<String, String> asMap() {
        return Map.copyOf(messages);
    }
}
