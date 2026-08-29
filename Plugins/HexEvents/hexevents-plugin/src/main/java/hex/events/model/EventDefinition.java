package hex.events.model;

import hex.events.api.EventModuleSettings;
import hex.events.api.ResultSubjectType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EventDefinition(
        String id,
        boolean enabled,
        String displayName,
        String description,
        String iconMaterial,
        String moduleId,
        EventModuleSettings moduleSettings,
        Schedule schedule,
        Duration duration,
        Duration prepareBefore,
        RegistrationPolicy registration,
        LobbyPolicy lobby,
        CapacityPolicy capacity,
        JoinPolicy join,
        BossBarPolicy bossBar,
        List<RequirementSpec> requirements,
        List<CostSpec> costs,
        List<RewardRule> rewards,
        List<String> rewardDescriptions,
        List<String> exclusiveGroups,
        Map<String, Object> snapshot
) {
    public EventDefinition {
        bossBar = bossBar == null ? BossBarPolicy.defaults() : bossBar;
        requirements = List.copyOf(requirements);
        costs = List.copyOf(costs);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        rewardDescriptions = List.copyOf(rewardDescriptions);
        exclusiveGroups = List.copyOf(exclusiveGroups);
        snapshot = deepMap(snapshot);
    }

    public enum ScheduleKind { RECURRING, ONE_TIME }

    public record Schedule(ZoneId zoneId, List<WeeklySlot> weekly, List<OneTimeSlot> oneTime) {
        public Schedule {
            weekly = weekly == null ? List.of() : List.copyOf(weekly);
            oneTime = oneTime == null ? List.of() : List.copyOf(oneTime);
        }
        public Schedule(ZoneId zoneId, List<WeeklySlot> weekly) {
            this(zoneId, weekly, List.of());
        }
        public ScheduleKind kindAt(Instant occurrenceAt) {
            boolean explicitOneTime = oneTime.stream()
                    .anyMatch(slot -> slot.dateTime().atZone(zoneId).toInstant().equals(occurrenceAt));
            return explicitOneTime ? ScheduleKind.ONE_TIME : ScheduleKind.RECURRING;
        }
    }
    public record WeeklySlot(DayOfWeek day, LocalTime time) { }
    public record OneTimeSlot(LocalDateTime dateTime) { }

    public enum RegistrationMode { DISABLED, OPTIONAL, REQUIRED }
    public enum CancelUntil { START, LOBBY_START, NEVER }
    public record RegistrationPolicy(RegistrationMode mode, Duration opensBefore, CancelUntil cancelUntil) {
        public boolean enabled() { return mode != RegistrationMode.DISABLED; }
        public boolean required() { return mode == RegistrationMode.REQUIRED; }
    }

    public record LobbyPolicy(boolean enabled, Duration duration) { }
    public enum TooFewPolicy { CANCEL_AND_REFUND, CANCEL_NO_REFUND, START_ANYWAY }
    public record CapacityPolicy(int minPlayers, int maxPlayers, TooFewPolicy onTooFew) { }
    public enum LateJoinScope { REGISTERED_ONLY, ELIGIBLE_PLAYERS }
    public record JoinPolicy(boolean autoJoinRegistered, boolean lateJoin, Duration lateJoinFor, LateJoinScope lateJoinScope, boolean manualEntry) { }

    public record BossBarPolicy(boolean enabled, Duration showBefore, String title, String color, String style) {
        private static final String DEFAULT_TITLE = "&e{event} &7startuje za &f{time}";

        public BossBarPolicy {
            showBefore = showBefore == null ? Duration.ofMinutes(30) : showBefore;
            title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
            color = color == null || color.isBlank() ? "YELLOW" : color.toUpperCase(java.util.Locale.ROOT);
            style = style == null || style.isBlank() ? "SOLID" : style.toUpperCase(java.util.Locale.ROOT);
        }

        public static BossBarPolicy defaults() {
            return new BossBarPolicy(true, Duration.ofMinutes(30), DEFAULT_TITLE, "YELLOW", "SOLID");
        }
    }
    public record RequirementSpec(String type, EventModuleSettings settings) { }
    public record CostSpec(String id, String type, EventModuleSettings settings) { }

    public enum RewardSelectorType { TOP_N, TOP_PERCENT, PARTICIPATION, REMAINING_ELIGIBLE, WINNER }
    public enum RewardAmountType { FIXED, METRIC_SCALE, POOL_SHARE }

    public record RewardSelector(
            RewardSelectorType type,
            String metric,
            int n,
            double percent,
            int minimumWinners,
            int maximumWinners,
            List<String> excludeRuleIds
    ) {
        public RewardSelector {
            metric = metric == null || metric.isBlank() ? "damage" : metric;
            excludeRuleIds = excludeRuleIds == null ? List.of() : List.copyOf(excludeRuleIds);
        }
    }

    public record RewardAmount(
            RewardAmountType type,
            String metric,
            BigDecimal fixed,
            BigDecimal base,
            BigDecimal perUnit,
            BigDecimal pool,
            BigDecimal min,
            BigDecimal max,
            RoundingMode roundingMode
    ) {
        public RewardAmount {
            metric = metric == null || metric.isBlank() ? "damage" : metric;
            fixed = nz(fixed); base = nz(base); perUnit = nz(perUnit); pool = nz(pool);
            roundingMode = roundingMode == null ? RoundingMode.DOWN : roundingMode;
        }
        private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    }

    public record RewardGrantSpec(String type, RewardAmount amount, EventModuleSettings settings) {
        public RewardGrantSpec {
            settings = settings == null ? EventModuleSettings.empty() : settings;
        }
    }

    public record RewardRule(
            String id,
            ResultSubjectType target,
            RewardSelector selector,
            List<RewardGrantSpec> grants
    ) {
        public RewardRule {
            target = target == null ? ResultSubjectType.PLAYER : target;
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }

    /**
     * Backwards-compatible constructor used by pure logic tests and older event modules.
     * Event definitions created programmatically without boss-bar settings receive the
     * same default as YAML definitions: enabled, 30 minutes before the public start.
     */
    public EventDefinition(
            String id, boolean enabled, String displayName, String description, String iconMaterial,
            String moduleId, EventModuleSettings moduleSettings, Schedule schedule, Duration duration,
            Duration prepareBefore, RegistrationPolicy registration, LobbyPolicy lobby, CapacityPolicy capacity,
            JoinPolicy join, List<RequirementSpec> requirements, List<CostSpec> costs, List<RewardRule> rewards,
            List<String> rewardDescriptions, List<String> exclusiveGroups, Map<String, Object> snapshot
    ) {
        this(id, enabled, displayName, description, iconMaterial, moduleId, moduleSettings, schedule, duration,
                prepareBefore, registration, lobby, capacity, join, BossBarPolicy.defaults(), requirements, costs,
                rewards, rewardDescriptions, exclusiveGroups, snapshot);
    }

    private static Map<String, Object> deepMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, deepFreeze(value)));
        return Collections.unmodifiableMap(out);
    }

    private static Object deepFreeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, nested) -> out.put(String.valueOf(key), deepFreeze(nested)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(EventDefinition::deepFreeze).toList());
        }
        return value;
    }
}
