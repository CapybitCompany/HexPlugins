package hex.endevent.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record EndEventConfig(
        boolean enabled,
        ZoneId zoneId,
        Duration duration,
        Duration prepareBefore,
        List<ScheduleEntry> schedule,
        boolean blockAllEndEnvironments,
        String bypassPermission,
        Duration blockedMessageCooldown,
        String endWorld,
        String returnWorld,
        boolean resetBeforeEachEvent,
        SeedMode seedMode,
        long fixedSeed,
        boolean unloadAfterClose,
        BossBarConfig bossBar,
        String runtimeStateFile
) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Pattern SAFE_WORLD_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.ya?ml");

    public enum SeedMode { RANDOM, FIXED }

    public static EndEventConfig safeClosedDefaults() {
        return new EndEventConfig(
                false,
                ZoneId.of("Europe/Warsaw"),
                Duration.ofMinutes(120),
                Duration.ofMinutes(5),
                List.of(
                        new ScheduleEntry(DayOfWeek.TUESDAY, LocalTime.of(18, 0)),
                        new ScheduleEntry(DayOfWeek.FRIDAY, LocalTime.of(19, 0)),
                        new ScheduleEntry(DayOfWeek.SUNDAY, LocalTime.of(17, 0))
                ),
                true,
                "hexendevent.bypass",
                Duration.ofSeconds(3),
                "world_the_end",
                "world",
                true,
                SeedMode.RANDOM,
                0L,
                true,
                new BossBarConfig(true, 20, "PURPLE", "PROGRESS"),
                "runtime.yml"
        );
    }

    public record ScheduleEntry(DayOfWeek day, LocalTime time) { }

    public record BossBarConfig(boolean enabled, int updateIntervalTicks, String color, String overlay) { }

    public static EndEventConfig load(FileConfiguration config) {
        List<String> errors = new ArrayList<>();

        boolean enabled = config.getBoolean("event.enabled", true);
        ZoneId zoneId = parseZone(config.getString("event.timezone", "Europe/Warsaw"), errors);
        int durationMinutes = config.getInt("event.duration-minutes", 120);
        int prepareMinutes = config.getInt("event.prepare-before-minutes", 5);
        if (durationMinutes <= 0) errors.add("event.duration-minutes musi byc > 0");
        if (prepareMinutes < 0) errors.add("event.prepare-before-minutes musi byc >= 0");

        // LEGACY ONLY: harmonogram publicznego End Eventu jest źródłem prawdy w HexEvents/events.yml.
        // Odczytujemy starą sekcję wyłącznie dla kompatybilności ze starszymi configami/utility,
        // ale jej brak ani nakładanie się wpisów nie może już zablokować HexEndEvent.
        List<ScheduleEntry> schedule = loadLegacySchedule(config);
        Duration duration = Duration.ofMinutes(Math.max(1, durationMinutes));

        String bypass = trimOr(config.getString("access.bypass-permission"), "hexendevent.bypass");
        int cooldownSeconds = Math.max(0, config.getInt("access.blocked-message-cooldown-seconds", 3));
        String endWorld = trimOr(config.getString("world.end-world"), "world_the_end");
        String returnWorld = trimOr(config.getString("world.return-world"), "world");
        validateWorldName("world.end-world", endWorld, errors);
        validateWorldName("world.return-world", returnWorld, errors);
        if (endWorld.equals(returnWorld)) errors.add("world.end-world i world.return-world nie moga byc takie same");

        SeedMode seedMode;
        try {
            seedMode = SeedMode.valueOf(trimOr(config.getString("world.seed-mode"), "RANDOM").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            seedMode = SeedMode.RANDOM;
            errors.add("world.seed-mode musi byc RANDOM albo FIXED");
        }

        String stateFile = trimOr(config.getString("runtime.state-file"), "runtime.yml");
        if (!SAFE_FILE_NAME.matcher(stateFile).matches() || stateFile.contains("..")) {
            errors.add("runtime.state-file musi byc prosta nazwa pliku .yml bez sciezki");
        }

        int bossInterval = Math.max(20, config.getInt("bossbar.update-interval-ticks", 20));
        BossBarConfig bossBar = new BossBarConfig(
                config.getBoolean("bossbar.enabled", true),
                bossInterval,
                trimOr(config.getString("bossbar.color"), "PURPLE").toUpperCase(Locale.ROOT),
                trimOr(config.getString("bossbar.overlay"), "PROGRESS").toUpperCase(Locale.ROOT)
        );

        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));

        return new EndEventConfig(
                enabled,
                zoneId,
                duration,
                Duration.ofMinutes(prepareMinutes),
                List.copyOf(schedule),
                config.getBoolean("access.block-all-end-environments", true),
                bypass,
                Duration.ofSeconds(cooldownSeconds),
                endWorld,
                returnWorld,
                config.getBoolean("world.reset-before-each-event", true),
                seedMode,
                config.getLong("world.fixed-seed", 0L),
                config.getBoolean("world.unload-after-close", true),
                bossBar,
                stateFile
        );
    }

    private static ZoneId parseZone(String raw, List<String> errors) {
        try {
            return ZoneId.of(trimOr(raw, "Europe/Warsaw"));
        } catch (Exception ex) {
            errors.add("Niepoprawne event.timezone: " + raw);
            return ZoneId.of("Europe/Warsaw");
        }
    }

    private static List<ScheduleEntry> loadLegacySchedule(FileConfiguration config) {
        List<?> raw = config.getList("event.schedule", List.of());
        List<ScheduleEntry> result = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (Object value : raw) {
            if (!(value instanceof java.util.Map<?, ?> map)) continue;
            String dayRaw = String.valueOf(map.get("day"));
            String timeRaw = String.valueOf(map.get("time"));
            try {
                DayOfWeek day = DayOfWeek.valueOf(dayRaw.trim().toUpperCase(Locale.ROOT));
                LocalTime time = LocalTime.parse(timeRaw.trim(), TIME);
                String key = day + "@" + time;
                if (duplicates.add(key)) result.add(new ScheduleEntry(day, time));
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // Ignorowane świadomie: lokalny schedule nie steruje już eventem.
            }
        }
        result.sort(Comparator.comparing(ScheduleEntry::day).thenComparing(ScheduleEntry::time));
        return List.copyOf(result);
    }

    private static void validateScheduleOverlap(List<ScheduleEntry> schedule, Duration duration, List<String> errors) {
        if (schedule.size() < 2 || duration.isNegative() || duration.isZero()) return;
        long weekMinutes = 7L * 24L * 60L;
        List<Long> starts = schedule.stream()
                .map(e -> (long) (e.day().getValue() - 1) * 24L * 60L + e.time().getHour() * 60L + e.time().getMinute())
                .sorted()
                .toList();
        long durationMinutes = duration.toMinutes();
        for (int i = 0; i < starts.size(); i++) {
            long current = starts.get(i);
            long next = i + 1 < starts.size() ? starts.get(i + 1) : starts.get(0) + weekMinutes;
            if (current + durationMinutes > next) {
                errors.add("Okna event.schedule nakladaja sie przy duration-minutes=" + durationMinutes);
                return;
            }
        }
    }

    public List<String> validateLoadedWorlds(org.bukkit.Server server) {
        List<String> errors = new ArrayList<>();
        World normal = server.getWorld(returnWorld);
        if (normal != null && normal.getEnvironment() != World.Environment.NORMAL) {
            errors.add("world.return-world jest zaladowany, ale nie jest Environment.NORMAL");
        }
        World end = server.getWorld(endWorld);
        if (end != null && end.getEnvironment() != World.Environment.THE_END) {
            errors.add("world.end-world jest zaladowany, ale nie jest Environment.THE_END");
        }
        return errors;
    }

    private static void validateWorldName(String key, String value, List<String> errors) {
        if (!SAFE_WORLD_NAME.matcher(value).matches() || value.equals(".") || value.equals("..") || value.contains("..")) {
            errors.add(key + " musi byc prosta, bezpieczna nazwa swiata bez sciezki");
        }
    }

    private static String trimOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
