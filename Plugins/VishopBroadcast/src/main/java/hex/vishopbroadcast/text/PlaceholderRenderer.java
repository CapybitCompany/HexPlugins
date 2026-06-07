package hex.vishopbroadcast.text;

import java.util.Map;

public final class PlaceholderRenderer {
    private PlaceholderRenderer() {
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }
}

