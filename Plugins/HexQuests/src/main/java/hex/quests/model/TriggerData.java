package hex.quests.model;

import hex.core.api.messaging.HexMessageData;

import java.util.Optional;
import java.util.UUID;

public final class TriggerData {
    private TriggerData() {}

    public static Optional<UUID> playerUuid(HexMessageData data) {
        String value = string(data, "player-uuid", "");
        if (value.isBlank()) value = string(data, "playerUuid", "");
        if (value.isBlank()) value = string(data, "uuid", "");
        try { return value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public static String string(HexMessageData data, String key, String def) {
        if (data.has(key)) return data.getString(key, def);
        HexMessageData context = data.getSection("context");
        if (context.has(key)) return context.getString(key, def);
        HexMessageData actor = data.getSection("actor");
        if (actor.has(key)) return actor.getString(key, def);
        HexMessageData payload = data.getSection("data");
        if (payload.has(key)) return payload.getString(key, def);
        return def;
    }

    public static long longValue(HexMessageData data, String key, long def) {
        if (data.has(key)) return data.getLong(key, def);
        HexMessageData payload = data.getSection("data");
        return payload.has(key) ? payload.getLong(key, def) : def;
    }
}
