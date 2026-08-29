package hex.events.placeholder;

import hex.events.api.EventState;
import hex.events.lifecycle.EventLifecycleService;
import hex.events.model.EventInstance;
import hex.events.registration.AdmissionStatus;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * PlaceholderAPI view over the in-memory HexEvents runtime. No placeholder performs SQL.
 */
public final class HexEventsPlaceholderExpansion extends PlaceholderExpansion {
    private static final String NONE = "-";
    private static final String UNLIMITED = "∞";

    private final Plugin plugin;
    private final EventLifecycleService lifecycle;

    public HexEventsPlaceholderExpansion(Plugin plugin, EventLifecycleService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    @Override public @NotNull String getIdentifier() { return "hexevents"; }
    @Override public @NotNull String getAuthor() { return "Hex"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase();
        Optional<EventInstance> next = lifecycle.nextEvent();

        String fixed = switch (key) {
            case "next_name" -> next.map(i -> strip(i.definition().displayName())).orElse(NONE);
            case "next_start" -> next.map(i -> DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(lifecycle.engineConfig().displayZone()).format(i.startAt())).orElse(NONE);
            case "next_time" -> next.map(i -> DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(lifecycle.engineConfig().displayZone()).format(i.startAt())).orElse(NONE);
            case "next_date" -> next.map(i -> DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    .withZone(lifecycle.engineConfig().displayZone()).format(i.startAt())).orElse(NONE);
            case "next_relative" -> next.map(i -> Math.max(0, Duration.between(Instant.now(), i.startAt()).toMinutes()) + " min").orElse(NONE);
            case "next_duration" -> next.map(i -> i.definition().duration().toMinutes() + " min").orElse(NONE);
            case "next_capacity_max" -> next.map(HexEventsPlaceholderExpansion::capacityMax).orElse(NONE);
            case "next_capacity_used" -> next.map(i -> String.valueOf(i.participants().size())).orElse(NONE);
            case "next_capacity_available" -> next.map(HexEventsPlaceholderExpansion::capacityAvailable).orElse(NONE);
            case "active_count" -> String.valueOf(lifecycle.allInstances().stream().filter(i -> i.state() == EventState.RUNNING).count());
            case "today_count" -> String.valueOf(lifecycle.upcomingDays(1, lifecycle.engineConfig().displayZone()).size());
            case "player_queue_position" -> playerValue(player, PlayerValue.QUEUE_POSITION);
            case "player_queue_size" -> playerValue(player, PlayerValue.QUEUE_SIZE);
            case "player_queue_priority" -> playerValue(player, PlayerValue.QUEUE_PRIORITY);
            case "player_admission_status" -> playerValue(player, PlayerValue.ADMISSION_STATUS);
            case "player_registration_position" -> playerValue(player, PlayerValue.REGISTRATION_POSITION);
            case "player_registration_count" -> playerValue(player, PlayerValue.REGISTRATION_COUNT);
            default -> null;
        };
        if (fixed != null) return fixed;

        String dynamic = dynamicEvent(key);
        if (dynamic != null) return dynamic;
        return dynamicInstance(player, key);
    }

    private String playerValue(OfflinePlayer player, PlayerValue type) {
        if (player == null || player.getUniqueId() == null) return NONE;
        UUID playerId = player.getUniqueId();
        Optional<EventInstance> relevant = lifecycle.playerRelevantEvent(playerId);
        if (relevant.isEmpty()) return NONE;
        EventInstance instance = relevant.get();
        return switch (type) {
            case QUEUE_POSITION -> numberOrDash(lifecycle.queuePosition(instance, playerId));
            case QUEUE_SIZE -> String.valueOf(lifecycle.queueSize(instance));
            case QUEUE_PRIORITY -> lifecycle.queuePriority(instance, playerId);
            case ADMISSION_STATUS -> {
                AdmissionStatus status = lifecycle.admissionStatus(instance, playerId);
                yield status == null ? NONE : status.name();
            }
            case REGISTRATION_POSITION -> numberOrDash(lifecycle.registrationPosition(instance, playerId));
            case REGISTRATION_COUNT -> String.valueOf(instance.registeredPlayers().size());
        };
    }

    private String dynamicEvent(String key) {
        if (!key.startsWith("event_")) return null;
        for (String suffix : new String[]{"_capacity_max", "_capacity_used", "_capacity_available", "_queue_size"}) {
            if (!key.endsWith(suffix)) continue;
            String eventId = key.substring("event_".length(), key.length() - suffix.length());
            if (eventId.isBlank()) return NONE;
            Optional<EventInstance> instance = lifecycle.activeEvent(eventId).or(() -> lifecycle.nextEvent(eventId));
            if (instance.isEmpty()) return NONE;
            EventInstance i = instance.get();
            return switch (suffix) {
                case "_capacity_max" -> capacityMax(i);
                case "_capacity_used" -> String.valueOf(i.participants().size());
                case "_capacity_available" -> capacityAvailable(i);
                case "_queue_size" -> String.valueOf(lifecycle.queueSize(i));
                default -> NONE;
            };
        }
        return null;
    }

    private String dynamicInstance(OfflinePlayer player, String key) {
        if (!key.startsWith("instance_") || !key.endsWith("_queue_position")) return null;
        if (player == null || player.getUniqueId() == null) return NONE;
        String rawId = key.substring("instance_".length(), key.length() - "_queue_position".length());
        try {
            UUID instanceId = UUID.fromString(rawId);
            return lifecycle.instance(instanceId)
                    .map(i -> numberOrDash(lifecycle.queuePosition(i, player.getUniqueId())))
                    .orElse(NONE);
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }

    private static String capacityMax(EventInstance i) {
        int max = i.definition().capacity().maxPlayers();
        return max <= 0 ? UNLIMITED : String.valueOf(max);
    }

    private static String capacityAvailable(EventInstance i) {
        int max = i.definition().capacity().maxPlayers();
        if (max <= 0) return UNLIMITED;
        return String.valueOf(Math.max(0, max - i.participants().size()));
    }

    private static String numberOrDash(int value) { return value <= 0 ? NONE : String.valueOf(value); }
    private static String strip(String s) { return s == null ? "" : s.replaceAll("(?i)&[0-9A-FK-ORX]", ""); }

    private enum PlayerValue {
        QUEUE_POSITION,
        QUEUE_SIZE,
        QUEUE_PRIORITY,
        ADMISSION_STATUS,
        REGISTRATION_POSITION,
        REGISTRATION_COUNT
    }
}
