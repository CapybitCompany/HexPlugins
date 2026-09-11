package hex.minigames.config;

import hex.minigames.util.Text;

import java.util.Map;

public final class Messages {
    private final Map<String, String> values;

    public Messages(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public String raw(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public String get(String key, String fallback) {
        return Text.color(prefix() + raw(key, fallback));
    }

    public String plain(String key, String fallback) {
        return Text.color(raw(key, fallback));
    }

    public String format(String key, String fallback, Map<String, String> replacements) {
        String message = raw(key, fallback);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return Text.color(prefix() + message);
    }

    public String prefix() {
        return raw("prefix", "");
    }
}
