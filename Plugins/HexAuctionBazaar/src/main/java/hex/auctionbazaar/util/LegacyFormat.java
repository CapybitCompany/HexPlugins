package hex.auctionbazaar.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class LegacyFormat {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private LegacyFormat() {
    }

    public static Component component(String value) {
        if (value == null || value.isEmpty()) {
            return Component.empty();
        }
        return LEGACY.deserialize(value);
    }

    public static List<Component> components(List<String> values) {
        List<Component> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) {
            out.add(component(v));
        }
        return out;
    }

    public static String replace(String template, String placeholder, String value) {
        if (template == null) return "";
        return template.replace(placeholder, value == null ? "" : value);
    }
}
