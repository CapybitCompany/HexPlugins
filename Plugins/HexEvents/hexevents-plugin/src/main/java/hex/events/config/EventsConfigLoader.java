package hex.events.config;

import hex.events.api.EventModuleSettings;
import hex.events.model.EventDefinition;
import hex.events.util.DurationParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import hex.events.api.ResultSubjectType;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EventsConfigLoader {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final List<DateTimeFormatter> ONE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    );
    private final Plugin plugin;

    public EventsConfigLoader(Plugin plugin) { this.plugin = plugin; }

    public LoadResult load() {
        File file = new File(plugin.getDataFolder(), "events.yml");
        if (!file.exists()) plugin.saveResource("events.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> errors = new ArrayList<>();
        Map<String, EventDefinition> definitions = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("events");
        if (root == null) {
            errors.add("Brak sekcji 'events' w events.yml");
            return new LoadResult(false, new EventsConfig(Map.of()), List.copyOf(errors));
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                errors.add(id + ": definicja musi być sekcją YAML");
                continue;
            }
            try {
                EventDefinition definition = parseDefinition(id, section);
                definitions.put(id, definition);
            } catch (Exception ex) {
                errors.add(id + ": " + rootMessage(ex));
            }
        }

        return new LoadResult(errors.isEmpty(), new EventsConfig(definitions), List.copyOf(errors));
    }

    /**
     * Odtwarza definicję z immutable snapshotu zapisanego razem z EventInstance.
     * Dzięki temu restart/reload nie zmienia zasad instancji, na którą gracze już
     * się zapisali lub która rozpoczęła lifecycle.
     */
    public static EventDefinition parseSnapshot(String id, String rawSnapshot) {
        if (rawSnapshot == null || rawSnapshot.isBlank()) throw new IllegalArgumentException("pusty config snapshot");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(rawSnapshot));
        ConfigurationSection section = yaml.getConfigurationSection("snapshot");
        if (section == null) throw new IllegalArgumentException("snapshot bez sekcji root");
        return parseDefinition(id, section);
    }

    public static EventDefinition parseDefinition(String id, ConfigurationSection s) {
        if (!id.matches("[a-z0-9_.:-]+")) throw new IllegalArgumentException("niepoprawne event id");
        boolean enabled = s.getBoolean("enabled", true);
        String displayName = nonBlank(s.getString("name"), id);
        String description = nonBlank(s.getString("description"), "");
        String icon = nonBlank(s.getString("icon"), "CLOCK").toUpperCase(Locale.ROOT);
        String module = nonBlank(s.getString("module"), "");
        if (module.isBlank()) throw new IllegalArgumentException("brak module");

        EventModuleSettings moduleSettings = new EventModuleSettings(sectionMap(s.getConfigurationSection("module-settings")));

        ConfigurationSection scheduleSection = requiredSection(s, "schedule");
        ZoneId zone = ZoneId.of(nonBlank(scheduleSection.getString("timezone"), "Europe/Warsaw"));
        List<EventDefinition.WeeklySlot> weekly = parseWeekly(scheduleSection);
        List<EventDefinition.OneTimeSlot> oneTime = parseOneTime(scheduleSection);
        if (weekly.isEmpty() && oneTime.isEmpty())
            throw new IllegalArgumentException("schedule musi zawierać co najmniej schedule.weekly lub schedule.one-time");

        Duration duration = DurationParser.parse(s.getString("duration", "60m"), Duration.ofHours(1));
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration musi być > 0");
        Duration prepareBefore = DurationParser.parse(s.getString("prepare-before", "0m"), Duration.ZERO);
        if (prepareBefore.isNegative()) throw new IllegalArgumentException("prepare-before musi być >= 0");

        ConfigurationSection reg = s.getConfigurationSection("registration");
        EventDefinition.RegistrationMode regMode = enumValue(
                reg == null ? "DISABLED" : reg.getString("mode", "DISABLED"),
                EventDefinition.RegistrationMode.class, "registration.mode");
        Duration opensBefore = DurationParser.parse(reg == null ? "0m" : reg.getString("opens-before", "0m"), Duration.ZERO);
        EventDefinition.CancelUntil cancelUntil = enumValue(
                reg == null ? "START" : reg.getString("cancel-until", "START"),
                EventDefinition.CancelUntil.class, "registration.cancel-until");
        EventDefinition.RegistrationPolicy registration = new EventDefinition.RegistrationPolicy(regMode, opensBefore, cancelUntil);

        ConfigurationSection lobbySection = s.getConfigurationSection("lobby");
        boolean lobbyEnabled = lobbySection != null && lobbySection.getBoolean("enabled", false);
        Duration lobbyDuration = DurationParser.parse(lobbySection == null ? "0m" : lobbySection.getString("duration", "0m"), Duration.ZERO);
        if (lobbyEnabled && (lobbyDuration.isZero() || lobbyDuration.isNegative())) {
            throw new IllegalArgumentException("lobby.duration musi być > 0, gdy lobby.enabled=true");
        }
        EventDefinition.LobbyPolicy lobby = new EventDefinition.LobbyPolicy(lobbyEnabled, lobbyDuration);

        ConfigurationSection capacitySection = s.getConfigurationSection("capacity");
        int minPlayers = capacitySection == null ? 0 : Math.max(0, capacitySection.getInt("min-players", 0));
        int maxPlayers = capacitySection == null ? 0 : Math.max(0, capacitySection.getInt("max-players", 0));
        if (maxPlayers > 0 && minPlayers > maxPlayers) throw new IllegalArgumentException("capacity.min-players > max-players");
        EventDefinition.TooFewPolicy tooFew = enumValue(
                capacitySection == null ? "CANCEL_AND_REFUND" : capacitySection.getString("on-too-few", "CANCEL_AND_REFUND"),
                EventDefinition.TooFewPolicy.class, "capacity.on-too-few");
        EventDefinition.CapacityPolicy capacity = new EventDefinition.CapacityPolicy(minPlayers, maxPlayers, tooFew);

        ConfigurationSection joinSection = s.getConfigurationSection("join");
        boolean autoJoin = joinSection == null || joinSection.getBoolean("auto-join-registered", true);
        boolean lateJoin = joinSection != null && joinSection.getBoolean("late-join", false);
        Duration lateJoinFor = DurationParser.parse(joinSection == null ? "0m" : joinSection.getString("late-join-for", "0m"), Duration.ZERO);
        EventDefinition.LateJoinScope lateScope = enumValue(
                joinSection == null ? "REGISTERED_ONLY" : joinSection.getString("late-join-scope", "REGISTERED_ONLY"),
                EventDefinition.LateJoinScope.class, "join.late-join-scope");
        boolean manualEntry = joinSection == null || joinSection.getBoolean("manual-entry", true);
        EventDefinition.JoinPolicy join = new EventDefinition.JoinPolicy(autoJoin, lateJoin, lateJoinFor, lateScope, manualEntry);

        ConfigurationSection bossBarSection = s.getConfigurationSection("bossbar");
        if (bossBarSection == null) bossBarSection = s.getConfigurationSection("boss-bar"); // compatibility alias
        boolean bossBarEnabled = bossBarSection == null || bossBarSection.getBoolean("enabled", true);
        Duration bossBarShowBefore = DurationParser.parse(
                bossBarSection == null ? "30m" : bossBarSection.getString("show-before", "30m"), Duration.ofMinutes(30));
        if (bossBarShowBefore.isNegative() || (bossBarEnabled && bossBarShowBefore.isZero()))
            throw new IllegalArgumentException("bossbar.show-before musi być > 0, gdy bossbar.enabled=true");
        String bossBarTitle = nonBlank(bossBarSection == null ? null : bossBarSection.getString("title"),
                "&e{event} &7startuje za &f{time}");
        String bossBarColor = nonBlank(bossBarSection == null ? null : bossBarSection.getString("color"), "YELLOW").toUpperCase(Locale.ROOT);
        String bossBarStyle = nonBlank(bossBarSection == null ? null : bossBarSection.getString("style"), "SOLID").toUpperCase(Locale.ROOT);
        Set<String> bossBarColors = Set.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
        Set<String> bossBarStyles = Set.of("SOLID", "SEGMENTED_6", "SEGMENTED_10", "SEGMENTED_12", "SEGMENTED_20");
        if (!bossBarColors.contains(bossBarColor)) throw new IllegalArgumentException("niepoprawny bossbar.color: " + bossBarColor);
        if (!bossBarStyles.contains(bossBarStyle)) throw new IllegalArgumentException("niepoprawny bossbar.style: " + bossBarStyle);
        EventDefinition.BossBarPolicy bossBar = new EventDefinition.BossBarPolicy(
                bossBarEnabled, bossBarShowBefore, bossBarTitle, bossBarColor, bossBarStyle);

        List<EventDefinition.RequirementSpec> requirements = new ArrayList<>();
        int requirementIndex = 0;
        for (Map<?, ?> raw : s.getMapList("requirements")) {
            Map<String, Object> map = stringMap(raw);
            String type = nonBlank(string(map.remove("type")), "").toLowerCase(Locale.ROOT);
            if (type.isBlank()) throw new IllegalArgumentException("requirements[" + requirementIndex + "] bez type");
            requirements.add(new EventDefinition.RequirementSpec(type, new EventModuleSettings(map)));
            requirementIndex++;
        }

        List<EventDefinition.CostSpec> costs = new ArrayList<>();
        int costIndex = 0;
        for (Map<?, ?> raw : s.getMapList("costs")) {
            Map<String, Object> map = stringMap(raw);
            String type = nonBlank(string(map.remove("type")), "").toLowerCase(Locale.ROOT);
            if (type.isBlank()) throw new IllegalArgumentException("costs[" + costIndex + "] bez type");
            String costId = nonBlank(string(map.remove("id")), "cost_" + costIndex);
            costs.add(new EventDefinition.CostSpec(costId, type, new EventModuleSettings(map)));
            costIndex++;
        }

        List<EventDefinition.RewardRule> rewardRules = parseRewards(s);
        List<String> rewards = s.getStringList("reward-descriptions");
        List<String> exclusiveGroups = s.getStringList("exclusive-groups");

        Map<String, Object> snapshot = sectionMap(s);
        return new EventDefinition(id, enabled, displayName, description, icon, module, moduleSettings,
                new EventDefinition.Schedule(zone, weekly, oneTime), duration, prepareBefore, registration,
                lobby, capacity, join, bossBar, requirements, costs, rewardRules, rewards, exclusiveGroups, snapshot);
    }


    private static List<EventDefinition.RewardRule> parseRewards(ConfigurationSection s) {
        List<EventDefinition.RewardRule> out = new ArrayList<>();
        Set<String> seenRuleIds = new LinkedHashSet<>();
        int ruleIndex = 0;
        for (Map<?, ?> rawRule : s.getMapList("rewards")) {
            Map<String,Object> rule = stringMap(rawRule);
            String id = nonBlank(string(rule.get("id")), "reward_" + ruleIndex);
            if (!seenRuleIds.add(id)) throw new IllegalArgumentException("duplikat rewards.id: " + id);
            ResultSubjectType target = enumValue(string(rule.getOrDefault("target", "PLAYER")), ResultSubjectType.class, "rewards["+ruleIndex+"].target");
            Object selectorRaw = rule.get("selector");
            if (!(selectorRaw instanceof Map<?,?> selectorMapRaw)) throw new IllegalArgumentException("rewards["+ruleIndex+"] bez selector");
            Map<String,Object> selectorMap = stringMap(selectorMapRaw);
            EventDefinition.RewardSelectorType selectorType = enumValue(string(selectorMap.get("type")), EventDefinition.RewardSelectorType.class, "rewards["+ruleIndex+"].selector.type");
            String metric = nonBlank(string(selectorMap.get("metric")), "damage");
            int n = intValue(selectorMap.get("n"), intValue(selectorMap.get("count"), 1));
            double percent = doubleValue(selectorMap.get("percent"), 0.0);
            int minWinners = Math.max(0, intValue(selectorMap.get("minimum-winners"), 0));
            int maxWinners = Math.max(0, intValue(selectorMap.get("maximum-winners"), 0));
            List<String> exclude = stringList(selectorMap.get("exclude"));
            if (selectorType == EventDefinition.RewardSelectorType.REMAINING_ELIGIBLE) {
                for (String excludedRule : exclude) {
                    if (!seenRuleIds.contains(excludedRule) || excludedRule.equals(id))
                        throw new IllegalArgumentException("rewards["+ruleIndex+"] exclude odwołuje się do nieistniejącej/późniejszej reguły: " + excludedRule);
                }
            }
            if (selectorType == EventDefinition.RewardSelectorType.TOP_N && n <= 0) throw new IllegalArgumentException("rewards["+ruleIndex+"] TOP_N wymaga n > 0");
            if (selectorType == EventDefinition.RewardSelectorType.TOP_PERCENT && (percent <= 0 || percent > 100)) throw new IllegalArgumentException("rewards["+ruleIndex+"] TOP_PERCENT wymaga percent 0..100");
            EventDefinition.RewardSelector selector = new EventDefinition.RewardSelector(selectorType, metric, n, percent, minWinners, maxWinners, exclude);

            List<EventDefinition.RewardGrantSpec> grants = new ArrayList<>();
            Object grantsRaw = rule.get("grants");
            if (!(grantsRaw instanceof List<?> grantsList) || grantsList.isEmpty()) throw new IllegalArgumentException("rewards["+ruleIndex+"] bez grants");
            int grantIndex = 0;
            for (Object grantRaw : grantsList) {
                if (!(grantRaw instanceof Map<?,?> gmRaw)) throw new IllegalArgumentException("rewards["+ruleIndex+"].grants["+grantIndex+"] musi być mapą");
                Map<String,Object> gm = stringMap(gmRaw);
                String type = nonBlank(string(gm.remove("type")), "").toLowerCase(Locale.ROOT);
                if (type.isBlank()) throw new IllegalArgumentException("rewards["+ruleIndex+"].grants["+grantIndex+"] bez type");
                Object amountRaw = gm.remove("amount");
                EventDefinition.RewardAmount amount = parseRewardAmount(amountRaw);
                grants.add(new EventDefinition.RewardGrantSpec(type, amount, new EventModuleSettings(gm)));
                grantIndex++;
            }
            out.add(new EventDefinition.RewardRule(id, target, selector, grants));
            ruleIndex++;
        }
        return List.copyOf(out);
    }

    private static EventDefinition.RewardAmount parseRewardAmount(Object raw) {
        if (raw instanceof Number || raw instanceof String) {
            return new EventDefinition.RewardAmount(EventDefinition.RewardAmountType.FIXED, "damage", decimal(raw, BigDecimal.ZERO), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, RoundingMode.DOWN);
        }
        Map<String,Object> m = raw instanceof Map<?,?> map ? stringMap(map) : Map.of();
        EventDefinition.RewardAmountType type = enumValue(string(m.getOrDefault("type", "FIXED")), EventDefinition.RewardAmountType.class, "reward amount.type");
        String metric = nonBlank(string(m.get("metric")), "damage");
        BigDecimal fixed = decimal(m.getOrDefault("value", m.getOrDefault("fixed", 0)), BigDecimal.ZERO);
        BigDecimal base = decimal(m.get("base"), BigDecimal.ZERO);
        BigDecimal perUnit = decimal(m.get("per-unit"), BigDecimal.ZERO);
        BigDecimal pool = decimal(m.get("pool"), BigDecimal.ZERO);
        BigDecimal min = m.containsKey("min") ? decimal(m.get("min"), null) : null;
        BigDecimal max = m.containsKey("max") ? decimal(m.get("max"), null) : null;
        RoundingMode round;
        try { round = RoundingMode.valueOf(nonBlank(string(m.get("round")), "DOWN").toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new IllegalArgumentException("niepoprawny reward amount.round: " + m.get("round")); }
        return new EventDefinition.RewardAmount(type, metric, fixed, base, perUnit, pool, min, max, round);
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) return fallback;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ex) { throw new IllegalArgumentException("niepoprawna liczba reward: " + value); }
    }
    private static int intValue(Object value, int fallback) { if (value instanceof Number n) return n.intValue(); try { return value==null?fallback:Integer.parseInt(String.valueOf(value)); } catch(Exception e){ return fallback; } }
    private static double doubleValue(Object value, double fallback) { if (value instanceof Number n) return n.doubleValue(); try { return value==null?fallback:Double.parseDouble(String.valueOf(value)); } catch(Exception e){ return fallback; } }
    private static List<String> stringList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().map(String::valueOf).toList(); }

    private static List<EventDefinition.OneTimeSlot> parseOneTime(ConfigurationSection schedule) {
        List<String> rawValues = new ArrayList<>();
        rawValues.addAll(schedule.getStringList("one-time"));
        rawValues.addAll(schedule.getStringList("once")); // compact compatibility alias
        List<EventDefinition.OneTimeSlot> result = new ArrayList<>();
        Set<LocalDateTime> duplicates = new java.util.HashSet<>();
        for (String raw : rawValues) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) continue;
            LocalDateTime parsed = null;
            for (DateTimeFormatter formatter : ONE_TIME_FORMATS) {
                try {
                    parsed = LocalDateTime.parse(value, formatter);
                    break;
                } catch (Exception ignored) { }
            }
            if (parsed == null) {
                throw new IllegalArgumentException("niepoprawny schedule.one-time: " + value +
                        " (użyj np. 2026-09-15T19:00:00)");
            }
            parsed = parsed.withNano(0);
            if (!duplicates.add(parsed)) throw new IllegalArgumentException("duplikat schedule.one-time: " + value);
            result.add(new EventDefinition.OneTimeSlot(parsed));
        }
        result.sort(java.util.Comparator.comparing(EventDefinition.OneTimeSlot::dateTime));
        return List.copyOf(result);
    }

    private static List<EventDefinition.WeeklySlot> parseWeekly(ConfigurationSection schedule) {
        List<EventDefinition.WeeklySlot> result = new ArrayList<>();
        Set<String> duplicates = new java.util.HashSet<>();
        for (Map<?, ?> raw : schedule.getMapList("weekly")) {
            String dayRaw = string(raw.get("day"));
            String timeRaw = string(raw.get("time"));
            DayOfWeek day = DayOfWeek.valueOf(dayRaw.trim().toUpperCase(Locale.ROOT));
            LocalTime time = LocalTime.parse(timeRaw.trim(), TIME);
            String key = day + "@" + time;
            if (!duplicates.add(key)) throw new IllegalArgumentException("duplikat schedule.weekly: " + key);
            result.add(new EventDefinition.WeeklySlot(day, time));
        }
        return result;
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) throw new IllegalArgumentException("brak sekcji " + path);
        return section;
    }

    private static <E extends Enum<E>> E enumValue(String raw, Class<E> type, String path) {
        try { return Enum.valueOf(type, nonBlank(raw, "").toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new IllegalArgumentException("niepoprawne " + path + ": " + raw); }
    }

    private static Map<String, Object> sectionMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) out.put(key, sectionMap(nested));
            else if (value instanceof Map<?, ?> map) out.put(key, stringMap(map));
            else if (value instanceof List<?> list) out.put(key, deepList(list));
            else out.put(key, value);
        }
        return out;
    }

    private static List<Object> deepList(List<?> list) {
        List<Object> out = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> map) out.add(stringMap(map));
            else if (value instanceof List<?> nested) out.add(deepList(nested));
            else out.add(value);
        }
        return out;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Map<?, ?> map) out.put(String.valueOf(k), stringMap(map));
            else if (v instanceof List<?> list) out.put(String.valueOf(k), deepList(list));
            else out.put(String.valueOf(k), v);
        });
        return out;
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record LoadResult(boolean success, EventsConfig config, List<String> errors) { }
}
