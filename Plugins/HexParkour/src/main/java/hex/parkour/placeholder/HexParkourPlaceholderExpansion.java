package hex.parkour.placeholder;

import hex.parkour.config.ParkourConfig;
import hex.parkour.model.ParkourAttempt;
import hex.parkour.model.ParkourPlayerSession;
import hex.parkour.persistence.ParkourTimeRecord;
import hex.parkour.persistence.ParkourTimesRepository;
import hex.parkour.service.ParkourSessionService;
import hex.parkour.util.DurationFormats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class HexParkourPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern TOP_SUFFIX = Pattern.compile("_(1|2|3)_(name|time)$");

    private final JavaPlugin plugin;
    private final ParkourSessionService sessions;
    private final ParkourTimesRepository times;

    public HexParkourPlaceholderExpansion(JavaPlugin plugin, ParkourSessionService sessions, ParkourTimesRepository times) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.times = times;
    }

    @Override
    public String getIdentifier() {
        return "hexparkour";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (identifier == null) return null;
        ParkourConfig config = sessions.config();
        if (config == null) return null;

        String key = identifier.toLowerCase(Locale.ROOT);
        if ("current_arena".equals(key)) return currentArena(player, config);
        if ("current_time".equals(key)) return currentTime(player, config);

        String myPrefix = "my_time_";
        if (key.startsWith(myPrefix)) {
            String arenaId = arenaId(identifier.substring(myPrefix.length()), config);
            return arenaId == null ? null : playerBest(player, arenaId, config);
        }

        String myBestPrefix = "my_best_time_";
        if (key.startsWith(myBestPrefix)) {
            String arenaId = arenaId(identifier.substring(myBestPrefix.length()), config);
            return arenaId == null ? null : playerBest(player, arenaId, config);
        }

        String myLastPrefix = "my_last_time_";
        if (key.startsWith(myLastPrefix)) {
            String arenaId = arenaId(identifier.substring(myLastPrefix.length()), config);
            return arenaId == null ? null : playerLast(player, arenaId, config);
        }

        String playerBestPrefix = "player_best_time_";
        if (key.startsWith(playerBestPrefix)) {
            ArenaSuffix parsed = arenaSuffix(identifier.substring(playerBestPrefix.length()), config);
            if (parsed == null || parsed.suffix().isBlank()) return null;
            return namedPlayerBest(parsed.suffix(), parsed.arenaId(), config);
        }

        if (key.startsWith("top_")) {
            return top(identifier.substring("top_".length()), config);
        }

        return null;
    }

    private String currentArena(Player player, ParkourConfig config) {
        ParkourPlayerSession session = player == null ? null : sessions.session(player);
        if (session == null || session.currentArenaId() == null) return config.placeholders().emptyName();
        return session.currentArenaId();
    }

    private String currentTime(Player player, ParkourConfig config) {
        ParkourPlayerSession session = player == null ? null : sessions.session(player);
        ParkourAttempt attempt = session == null ? null : session.attempt();
        if (attempt == null || !attempt.timerStarted()) return config.placeholders().emptyTime();
        return DurationFormats.formatNanos(attempt.elapsedNanos(System.nanoTime()), config.timerFormat());
    }

    private String playerBest(Player player, String arenaId, ParkourConfig config) {
        if (player == null) return config.placeholders().emptyTime();
        return times.find(player.getUniqueId(), arenaId)
                .map(record -> DurationFormats.formatMillis(record.bestTimeMillis(), config.timerFormat()))
                .orElse(config.placeholders().emptyTime());
    }

    private String playerLast(Player player, String arenaId, ParkourConfig config) {
        if (player == null) return config.placeholders().emptyTime();
        return times.find(player.getUniqueId(), arenaId)
                .map(record -> DurationFormats.formatMillis(record.lastTimeMillis(), config.timerFormat()))
                .orElse(config.placeholders().emptyTime());
    }

    private String namedPlayerBest(String playerKey, String arenaId, ParkourConfig config) {
        Optional<ParkourTimeRecord> record = parseUuid(playerKey)
                .flatMap(uuid -> times.find(uuid, arenaId));
        if (record.isEmpty()) record = times.findByPlayerName(playerKey, arenaId);
        return record
                .map(value -> DurationFormats.formatMillis(value.bestTimeMillis(), config.timerFormat()))
                .orElse(config.placeholders().emptyTime());
    }

    private String top(String raw, ParkourConfig config) {
        String lower = raw.toLowerCase(Locale.ROOT);
        Matcher matcher = TOP_SUFFIX.matcher(lower);
        if (!matcher.find()) return null;
        String arenaRaw = raw.substring(0, matcher.start());
        String arenaId = arenaId(arenaRaw, config);
        if (arenaId == null) return null;
        int position = Integer.parseInt(matcher.group(1));
        String field = matcher.group(2);
        List<ParkourTimeRecord> records = times.top(arenaId, 3);
        if (position < 1 || position > records.size()) {
            return "name".equals(field) ? config.placeholders().emptyName() : config.placeholders().emptyTime();
        }
        ParkourTimeRecord record = records.get(position - 1);
        return "name".equals(field)
                ? record.playerName()
                : DurationFormats.formatMillis(record.bestTimeMillis(), config.timerFormat());
    }

    private ArenaSuffix arenaSuffix(String raw, ParkourConfig config) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> arenaIds = config.arenas().keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String arenaId : arenaIds) {
            String arenaLower = arenaId.toLowerCase(Locale.ROOT);
            if (lower.equals(arenaLower)) return new ArenaSuffix(arenaId, "");
            if (lower.startsWith(arenaLower + "_")) {
                return new ArenaSuffix(arenaId, raw.substring(arenaId.length() + 1));
            }
        }
        return null;
    }

    private String arenaId(String raw, ParkourConfig config) {
        for (String arenaId : config.arenas().keySet()) {
            if (arenaId.equalsIgnoreCase(raw)) return arenaId;
        }
        return null;
    }

    private Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private record ArenaSuffix(String arenaId, String suffix) {
    }
}
