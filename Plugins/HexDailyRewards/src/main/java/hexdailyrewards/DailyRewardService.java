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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class DailyRewardService {

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
        return state(player.getUniqueId());
    }

    public ClaimState state(UUID playerId) {
        DailyRewardsConfig config = configSupplier.get();
        ZoneId zone = config.timeZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Optional<LocalDate> lastClaim = storage.lastClaimDate(playerId);
        boolean available = lastClaim.isEmpty() || lastClaim.get().isBefore(today);
        Instant nextReset = today.plusDays(1).atStartOfDay(zone).toInstant();
        Duration remaining = available ? Duration.ZERO : positive(Duration.between(clock.instant(), nextReset));
        return new ClaimState(available, today, lastClaim.orElse(null), nextReset, remaining);
    }

    public synchronized ClaimResult claim(Player player) {
        DailyRewardsConfig config = configSupplier.get();
        if (!config.enabled()) {
            return new ClaimResult(ClaimResult.Status.DISABLED, state(player), null);
        }
        ClaimState before = state(player);
        if (!before.available()) {
            return new ClaimResult(ClaimResult.Status.UNAVAILABLE, before, null);
        }
        Optional<ResolvedDailyReward> reward = currentReward(before.today());
        if (reward.isEmpty()) {
            plugin.getLogger().warning("HexDailyRewards: no reward configured for " + before.today());
            return new ClaimResult(ClaimResult.Status.NO_REWARD, before, null);
        }

        try {
            storage.markClaimed(player.getUniqueId(), player.getName(), before.today(), clock.instant());
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save daily reward claim for " + player.getName() + ": " + ex.getMessage());
            return new ClaimResult(ClaimResult.Status.ERROR, before, ex.getMessage());
        }

        ClaimState after = state(player);
        runRewardCommands(player, after, reward.get());
        return new ClaimResult(ClaimResult.Status.CLAIMED, after, null);
    }

    public Optional<ResolvedDailyReward> currentReward(ClaimState state) {
        return currentReward(state.today());
    }

    public Optional<ResolvedDailyReward> currentReward(LocalDate date) {
        DailyRewardsConfig.RewardsCalendar calendar = configSupplier.get().rewardsCalendar();
        DailyRewardsConfig.RewardDefinition override = calendar.dateOverrides().get(date);
        int cycleDay = cycleDay(date, calendar);
        if (override != null) {
            return Optional.of(new ResolvedDailyReward(override, cycleDay, true));
        }
        DailyRewardsConfig.RewardDefinition definition = calendar.days().get(cycleDay);
        return definition == null
                ? Optional.empty()
                : Optional.of(new ResolvedDailyReward(definition, cycleDay, false));
    }

    public Map<String, String> placeholders(Player player, ClaimState state) {
        DailyRewardsConfig config = configSupplier.get();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("date", state.today().format(DateTimeFormatter.ofPattern(config.timeFormat().datePattern())));
        values.put("last_date", state.lastClaimDate() == null
                ? "-"
                : state.lastClaimDate().format(DateTimeFormatter.ofPattern(config.timeFormat().datePattern())));
        values.put("time", formatRemaining(state.remaining(), config.timeFormat()));
        values.put("reset_time", resetTime(state.nextReset(), config));
        Optional<ResolvedDailyReward> reward = currentReward(state);
        values.put("reward_id", reward.map(value -> value.definition().id()).orElse("-"));
        values.put("reward_day", reward.map(value -> Integer.toString(value.cycleDay())).orElse("-"));
        values.put("reward_name", reward.map(value -> value.definition().displayName()).orElse("-"));
        values.put("reward_source", reward.map(value -> value.dateOverride() ? "date-override" : "cycle").orElse("-"));
        return values;
    }

    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
        long seconds = Math.max(0L, duration.toSeconds());
        if (seconds <= 0L) {
            return format.now();
        }

        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        if (hours > 0L) {
            return hours + format.hour() + " " + minutes + format.minute();
        }
        if (minutes > 0L) {
            return minutes + format.minute() + " " + secs + format.second();
        }
        return secs + format.second();
    }

    private static Duration positive(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
