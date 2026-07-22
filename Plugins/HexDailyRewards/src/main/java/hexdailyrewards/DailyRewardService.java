package hexdailyrewards;

import hexdailyrewards.config.DailyRewardsConfig;
import hexdailyrewards.storage.RewardStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class DailyRewardService {

    public static final String CLAIM_ALL_PERMISSION = "hexdailyrewards.claim.all";

    private final JavaPlugin plugin;
    private final RewardStorage storage;
    private final Supplier<DailyRewardsConfig> configSupplier;
    private Clock clock;

    public DailyRewardService(JavaPlugin plugin,
                              RewardStorage storage,
                              Supplier<DailyRewardsConfig> configSupplier,
                              Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ClaimState state(Player player) {
        return state(player.getUniqueId(), primaryRewardGroup(player).id());
    }

    public ClaimState state(Player player, String rewardGroupId) {
        return state(player.getUniqueId(), rewardGroupId);
    }

    public ClaimState state(UUID playerId) {
        return state(playerId, RewardStorage.DEFAULT_GROUP_ID);
    }

    public ClaimState state(UUID playerId, String rewardGroupId) {
        DailyRewardsConfig config = configSupplier.get();
        DailyRewardsConfig.RewardGroup group = rewardGroup(config, rewardGroupId)
                .orElseGet(() -> defaultRewardGroup(config));
        ZoneId zone = config.timeZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Optional<LocalDate> lastClaim = storage.lastClaimDate(playerId, group.id());
        boolean available = lastClaim.isEmpty() || lastClaim.get().isBefore(today);
        Instant nextReset = today.plusDays(1).atStartOfDay(zone).toInstant();
        Duration remaining = available ? Duration.ZERO : positive(Duration.between(clock.instant(), nextReset));
        return new ClaimState(group.id(), available, today, lastClaim.orElse(null), nextReset, remaining);
    }

    public synchronized ClaimResult claim(Player player) {
        return claim(player, primaryRewardGroup(player).id());
    }

    public synchronized ClaimResult claim(Player player, String rewardGroupId) {
        DailyRewardsConfig config = configSupplier.get();
        DailyRewardsConfig.RewardGroup group = rewardGroup(config, rewardGroupId)
                .orElseGet(() -> defaultRewardGroup(config));
        if (!config.enabled()) {
            return new ClaimResult(ClaimResult.Status.DISABLED, state(player, group.id()), null);
        }
        ClaimState before = state(player, group.id());
        if (!canAccess(player, group)) {
            return new ClaimResult(ClaimResult.Status.LOCKED, before, null);
        }
        if (!before.available()) {
            return new ClaimResult(ClaimResult.Status.UNAVAILABLE, before, null);
        }
        Optional<ResolvedDailyReward> reward = currentReward(group, before.today());
        if (reward.isEmpty()) {
            plugin.getLogger().warning("HexDailyRewards: no reward configured for " + before.today()
                    + " in group " + group.id());
            return new ClaimResult(ClaimResult.Status.NO_REWARD, before, null);
        }

        try {
            storage.markClaimed(player.getUniqueId(), player.getName(), group.id(), before.today(), clock.instant());
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save daily reward claim for " + player.getName()
                    + " in group " + group.id() + ": " + ex.getMessage());
            return new ClaimResult(ClaimResult.Status.ERROR, before, ex.getMessage());
        }

        ClaimState after = state(player, group.id());
        runRewardCommands(player, after, reward.get());
        return new ClaimResult(ClaimResult.Status.CLAIMED, after, null);
    }

    public Optional<ResolvedDailyReward> currentReward(ClaimState state) {
        return currentReward(state.rewardGroupId(), state.today());
    }

    public Optional<ResolvedDailyReward> currentReward(LocalDate date) {
        DailyRewardsConfig config = configSupplier.get();
        return currentReward(defaultRewardGroup(config), date);
    }

    public Optional<ResolvedDailyReward> currentReward(String rewardGroupId, LocalDate date) {
        DailyRewardsConfig config = configSupplier.get();
        DailyRewardsConfig.RewardGroup group = rewardGroup(config, rewardGroupId)
                .orElseGet(() -> defaultRewardGroup(config));
        return currentReward(group, date);
    }

    public List<DailyRewardsConfig.RewardGroup> rewardGroups() {
        return configSupplier.get().rewardGroups().values().stream()
                .filter(DailyRewardsConfig.RewardGroup::enabled)
                .toList();
    }

    public List<DailyRewardsConfig.RewardGroup> accessibleRewardGroups(Player player) {
        DailyRewardsConfig config = configSupplier.get();
        List<DailyRewardsConfig.RewardGroup> enabledGroups = config.rewardGroups().values().stream()
                .filter(DailyRewardsConfig.RewardGroup::enabled)
                .toList();
        if (enabledGroups.isEmpty()) {
            return List.of();
        }
        if (canClaimAll(player)) {
            return enabledGroups;
        }

        List<DailyRewardsConfig.RewardGroup> matched = enabledGroups.stream()
                .filter(group -> hasAnyPermission(player, group.permissions()))
                .toList();
        List<DailyRewardsConfig.RewardGroup> matchedRankGroups = matched.stream()
                .filter(group -> !group.fallbackAccess())
                .toList();
        if (!matchedRankGroups.isEmpty()) {
            return highestPriority(matchedRankGroups);
        }
        if (!matched.isEmpty()) {
            return matched;
        }

        List<DailyRewardsConfig.RewardGroup> fallbacks = enabledGroups.stream()
                .filter(DailyRewardsConfig.RewardGroup::fallbackAccess)
                .toList();
        return fallbacks.isEmpty() ? List.of(enabledGroups.get(0)) : fallbacks;
    }

    public boolean canAccess(Player player, DailyRewardsConfig.RewardGroup group) {
        if (canClaimAll(player)) {
            return true;
        }
        return accessibleRewardGroups(player).stream().anyMatch(accessible -> accessible.id().equals(group.id()));
    }

    public DailyRewardsConfig.RewardGroup primaryRewardGroup(Player player) {
        List<DailyRewardsConfig.RewardGroup> groups = accessibleRewardGroups(player);
        if (!groups.isEmpty()) {
            return groups.get(0);
        }
        return defaultRewardGroup(configSupplier.get());
    }

    public Map<String, String> placeholders(Player player, ClaimState state) {
        return placeholders(player.getUniqueId(), player.getName(), state);
    }

    public Map<String, String> placeholders(UUID playerId, String playerName, ClaimState state) {
        DailyRewardsConfig config = configSupplier.get();
        DailyRewardsConfig.RewardGroup group = rewardGroup(config, state.rewardGroupId())
                .orElseGet(() -> defaultRewardGroup(config));
        DailyRewardsConfig.PlaceholderTexts placeholderTexts = config.placeholderTexts();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", playerName == null || playerName.isBlank() ? placeholderTexts.noPlayer() : playerName);
        values.put("uuid", playerId.toString());
        values.put("group_id", group.id());
        values.put("reward_group", group.id());
        values.put("group_name", group.displayName());
        values.put("group_ranks", String.join(", ", group.ranks()));
        values.put("date", state.today().format(DateTimeFormatter.ofPattern(config.timeFormat().datePattern())));
        values.put("last_date", state.lastClaimDate() == null
                ? "-"
                : state.lastClaimDate().format(DateTimeFormatter.ofPattern(config.timeFormat().datePattern())));
        values.put("time", formatRemaining(state.remaining(), config.timeFormat()));
        long totalMinutes = roundedMinutes(state.remaining());
        values.put("hours", Long.toString(totalMinutes / 60L));
        values.put("minutes", Long.toString(totalMinutes % 60L));
        values.put("available", state.available() ? placeholderTexts.available() : placeholderTexts.unavailable());
        values.put("status", state.available() ? placeholderTexts.statusAvailable() : placeholderTexts.statusClaimed());
        values.put("player_status", state.available()
                ? placeholderTexts.playerStatusAvailable()
                : placeholderTexts.playerStatusClaimed());
        values.put("reset_time", resetTime(state.nextReset(), config));
        Optional<ResolvedDailyReward> reward = currentReward(group, state.today());
        values.put("reward_id", reward.map(value -> value.definition().id()).orElse("-"));
        values.put("reward_day", reward.map(value -> Integer.toString(value.cycleDay())).orElse("-"));
        values.put("reward_name", reward.map(value -> value.definition().displayName()).orElse(placeholderTexts.noReward()));
        values.put("reward_source", reward.map(value -> value.dateOverride() ? "date-override" : "cycle").orElse("-"));
        values.put("hologram_status", Text.apply(state.available()
                ? placeholderTexts.hologramStatusAvailable()
                : placeholderTexts.hologramStatusClaimed(), values));
        return values;
    }

    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private Optional<ResolvedDailyReward> currentReward(DailyRewardsConfig.RewardGroup group, LocalDate date) {
        DailyRewardsConfig.RewardsCalendar calendar = group.rewardsCalendar();
        DailyRewardsConfig.RewardDefinition override = calendar.dateOverrides().get(date);
        int cycleDay = cycleDay(date, calendar);
        if (override != null) {
            return Optional.of(new ResolvedDailyReward(group, override, cycleDay, true));
        }
        DailyRewardsConfig.RewardDefinition definition = calendar.days().get(cycleDay);
        return definition == null
                ? Optional.empty()
                : Optional.of(new ResolvedDailyReward(group, definition, cycleDay, false));
    }

    private Optional<DailyRewardsConfig.RewardGroup> rewardGroup(DailyRewardsConfig config, String rewardGroupId) {
        DailyRewardsConfig.RewardGroup group = config.rewardGroups().get(normalizeGroupId(rewardGroupId));
        if (group == null || !group.enabled()) {
            return Optional.empty();
        }
        return Optional.of(group);
    }

    private DailyRewardsConfig.RewardGroup defaultRewardGroup(DailyRewardsConfig config) {
        DailyRewardsConfig.RewardGroup defaultGroup = config.rewardGroups().get(RewardStorage.DEFAULT_GROUP_ID);
        if (defaultGroup != null && defaultGroup.enabled()) {
            return defaultGroup;
        }
        return config.rewardGroups().values().stream()
                .filter(DailyRewardsConfig.RewardGroup::enabled)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("HexDailyRewards has no enabled reward groups."));
    }

    private List<DailyRewardsConfig.RewardGroup> highestPriority(List<DailyRewardsConfig.RewardGroup> groups) {
        int highest = groups.stream()
                .mapToInt(DailyRewardsConfig.RewardGroup::priority)
                .max()
                .orElse(Integer.MIN_VALUE);
        List<DailyRewardsConfig.RewardGroup> out = new ArrayList<>();
        for (DailyRewardsConfig.RewardGroup group : groups) {
            if (group.priority() == highest) {
                out.add(group);
            }
        }
        out.sort(Comparator.comparing(DailyRewardsConfig.RewardGroup::id));
        return List.copyOf(out);
    }

    private boolean hasAnyPermission(Player player, List<String> permissions) {
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean canClaimAll(Player player) {
        return player.isOp() || player.hasPermission("hexdailyrewards.admin") || player.hasPermission(CLAIM_ALL_PERMISSION);
    }

    private String normalizeGroupId(String raw) {
        if (raw == null || raw.isBlank()) {
            return RewardStorage.DEFAULT_GROUP_ID;
        }
        return raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
    }

    private void runRewardCommands(Player player, ClaimState state, ResolvedDailyReward reward) {
        Map<String, String> placeholders = placeholders(player, state);
        for (String raw : reward.definition().commands()) {
            String command = Text.apply(raw, placeholders).trim();
            if (command.isEmpty()) {
                continue;
            }
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private int cycleDay(LocalDate date, DailyRewardsConfig.RewardsCalendar calendar) {
        long offset = ChronoUnit.DAYS.between(calendar.startDate(), date);
        return Math.floorMod(offset, calendar.cycleDays()) + 1;
    }

    private String resetTime(Instant instant, DailyRewardsConfig config) {
        ZonedDateTime reset = instant.atZone(config.timeZone());
        return reset.format(DateTimeFormatter.ofPattern(config.timeFormat().resetTimePattern()));
    }

    private String formatRemaining(Duration duration, DailyRewardsConfig.TimeFormat format) {
        long minutesTotal = roundedMinutes(duration);
        if (minutesTotal <= 0L) {
            return format.now();
        }

        long hours = minutesTotal / 60L;
        long minutes = minutesTotal % 60L;
        return hours + format.hour() + " " + minutes + format.minute();
    }

    private long roundedMinutes(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        if (seconds <= 0L) {
            return 0L;
        }
        return (seconds + 59L) / 60L;
    }

    private static Duration positive(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
