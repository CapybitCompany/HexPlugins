package pl.hexnetwork.hexnametags.persistence;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Stores Adventure components as Base64-encoded MiniMessage lines separated by \n.
 *
 * This avoids adding a JSON dependency to the plugin and keeps the DB column human-safe:
 * each physical line in the DB is one Base64 value, so MiniMessage colors, spaces and
 * literal new lines cannot break the format.
 */
public final class NameTagCodec {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private NameTagCodec() {
    }

    public static String encode(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> encoded = new ArrayList<>(lines.size());
        for (Component line : lines) {
            String mini = MINI_MESSAGE.serialize(line == null ? Component.empty() : line);
            encoded.add(ENCODER.encodeToString(mini.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join("\n", encoded);
    }

    public static List<Component> decode(String data) {
        if (data == null || data.isBlank()) {
            return List.of();
        }

        List<Component> lines = new ArrayList<>();
        for (String raw : data.split("\\R")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                String mini = new String(DECODER.decode(raw.trim()), StandardCharsets.UTF_8);
                lines.add(MINI_MESSAGE.deserialize(mini));
            } catch (IllegalArgumentException ignored) {
                // Compatibility fallback for hand-edited rows: treat raw value as MiniMessage.
                lines.add(MINI_MESSAGE.deserialize(raw.trim()));
            }
        }
        return lines;
    }
}
