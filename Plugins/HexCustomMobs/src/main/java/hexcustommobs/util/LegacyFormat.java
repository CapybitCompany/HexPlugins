package hexcustommobs.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LegacyFormat {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private LegacyFormat() {
    }

    public static Component component(String value) {
        if (value == null || value.isBlank()) {
            return Component.empty();
        }
        return LEGACY.deserialize(value);
    }

    public static List<Component> components(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Component> output = new ArrayList<>(values.size());
        for (String value : values) {
            output.add(component(value));
        }
        return List.copyOf(output);
    }

    public static String number(double value, boolean decimals) {
        if (decimals) {
            return String.format(Locale.US, "%.1f", value);
        }
        return String.valueOf((int) Math.round(value));
    }
}
