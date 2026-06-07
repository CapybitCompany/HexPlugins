package hex.core.api.trigger;

import hex.core.api.messaging.HexMessageData;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generic gameplay event used by config-driven systems such as quests, skills and collections.
 * It is transported through HexMessageBus on channel trigger.<triggerId>.
 */
public record GameTrigger(String triggerId, String sourcePlugin, HexMessageData data) {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern TRIGGER_ID = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final String CHANNEL_PREFIX = "trigger.";

    public GameTrigger {
        triggerId = normalizeTriggerId(triggerId);
        sourcePlugin = sourcePlugin == null || sourcePlugin.isBlank() ? "unknown" : sourcePlugin.trim();
        data = data == null ? HexMessageData.EMPTY : data;
    }

    public static GameTrigger of(String triggerId, String sourcePlugin, HexMessageData data) {
        return new GameTrigger(triggerId, sourcePlugin, data);
    }

    public static String channelOf(String triggerId) {
        return CHANNEL_PREFIX + normalizeTriggerId(triggerId);
    }

    public static String triggerIdFromChannel(String channel) {
        Objects.requireNonNull(channel, "channel");
        String normalized = channel.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(CHANNEL_PREFIX) ? normalized.substring(CHANNEL_PREFIX.length()) : normalizeTriggerId(normalized);
    }

    public static HexMessageData.Builder dataBuilder() {
        return HexMessageData.builder().put("schemaVersion", SCHEMA_VERSION);
    }

    private static String normalizeTriggerId(String triggerId) {
        Objects.requireNonNull(triggerId, "triggerId");
        String normalized = triggerId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(CHANNEL_PREFIX)) {
            normalized = normalized.substring(CHANNEL_PREFIX.length());
        }
        if (!TRIGGER_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid trigger id: " + triggerId);
        }
        return normalized;
    }
}

