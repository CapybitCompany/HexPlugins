package hexafkzone;

import hexafkzone.config.AfkZoneConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class AfkZoneService {

    private final JavaPlugin plugin;
    private final Supplier<AfkZoneConfig> configSupplier;
    private final Map<UUID, AfkSession> sessions = new LinkedHashMap<>();
    private Clock clock;
    private BukkitTask task;

    public AfkZoneService(JavaPlugin plugin, Supplier<AfkZoneConfig> configSupplier, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        stop();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sessions.containsKey(player.getUniqueId())) {
                clearAfkDisplays(player);
            }
        }
        sessions.clear();
    }

    public void updatePlayer(Player player) {
        updatePlayer(player, player.getLocation());
    }

    public void updatePlayer(Player player, Location location) {
        AfkZoneConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            exit(player);
            return;
        }
        if (config.region().contains(location)) {
            enter(player);
        } else {
            exit(player);
        }
    }

    public void remove(Player player) {
        sessions.remove(player.getUniqueId());
        clearAfkDisplays(player);
    }

    public boolean isAfk(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    Optional<AfkSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    AfkZoneConfig.RankProfile profileFor(Player player) {
        AfkZoneConfig config = configSupplier.get();
        if (player.isOp() || player.hasPermission("hexafkzone.admin")) {
            return operatorProfile(config);
        }
        return config.rankProfiles().stream()
                .filter(profile -> hasAnyPermission(player, profile.permissions()))
                .findFirst()
                .orElseGet(() -> fallbackProfile(config));
    }

    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void tickPlayer(Player player) {
        updatePlayer(player);
        AfkSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        tickSession(player, session);
    }

    private void tickAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player);
        }
    }

    private void enter(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            return;
        }
        AfkZoneConfig.RankProfile profile = profileFor(player);
        Instant now = clock.instant();
        AfkSession session = new AfkSession(player.getUniqueId(), profile.id(), now,
                now.plusSeconds(profile.rewardIntervalSeconds()));
        sessions.put(player.getUniqueId(), session);
        showZoneSubtitle(player, profile);
        sendTimerActionbar(player, session, profile);
    }

    private void exit(Player player) {
        AfkSession removed = sessions.remove(player.getUniqueId());
        if (removed != null) {
            clearAfkDisplays(player);
        }
    }

    private void tickSession(Player player, AfkSession session) {
        AfkZoneConfig.RankProfile profile = profile(session.profileId()).orElseGet(() -> profileFor(player));
        if (tryAwardRewardCycle(player, session, profile)) {
            return;
        }
        if (session.rewardMessageUntil() != null && session.rewardMessageUntil().isAfter(clock.instant())) {
            player.sendActionBar(Text.component(session.rewardMessage()));
            return;
        }
        sendTimerActionbar(player, session, profile);
    }

    private boolean tryAwardRewardCycle(Player player, AfkSession session, AfkZoneConfig.RankProfile profile) {
        Instant now = clock.instant();
        if (now.isBefore(session.nextRewardAt())) {
            return false;
        }

        AfkZoneConfig config = configSupplier.get();
        AfkZoneConfig.Rewards rewards = config.rewards();
        List<AfkZoneConfig.ChanceReward> awarded = rollChanceRewards(rewards.chanceRewards());
        Map<String, String> placeholders = placeholders(player, session, profile, awarded);
        runCommands(rewards.base().commands(), placeholders);
        for (AfkZoneConfig.ChanceReward reward : awarded) {
            runCommands(reward.commands(), placeholders);
        }

        session.nextRewardAt(now.plusSeconds(profile.rewardIntervalSeconds()));
        playRewardSound(player);
        String message = Text.apply(config.messages().rewardActionbar(), placeholders);
        session.rewardMessage(message);
        session.rewardMessageUntil(now.plusSeconds(config.rewardMessageSeconds()));
        player.sendActionBar(Text.component(message));
        return true;
    }

    private void showZoneSubtitle(Player player, AfkZoneConfig.RankProfile profile) {
        Map<String, String> placeholders = profilePlaceholders(player, profile);
        Component subtitle = Text.component(configSupplier.get().messages().zoneSubtitle(), placeholders);
        try {
            player.showTitle(Title.title(Component.empty(), subtitle,
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(86400), Duration.ZERO)));
        } catch (Throwable ignored) {
            // MockBukkit and older server APIs may not store title state; gameplay still uses actionbar.
        }
    }

    private void sendTimerActionbar(Player player, AfkSession session, AfkZoneConfig.RankProfile profile) {
        Map<String, String> placeholders = placeholders(player, session, profile, List.of());
        player.sendActionBar(Text.component(configSupplier.get().messages().timerActionbar(), placeholders));
    }

    private void clearAfkDisplays(Player player) {
        try {
            player.clearTitle();
        } catch (Throwable ignored) {
            // Keeps test/runtime compatibility if title state is not supported.
        }
        player.sendActionBar(Component.empty());
    }

    private void playRewardSound(Player player) {
        AfkZoneConfig.SoundSetting sound = configSupplier.get().sounds().reward();
        if (!sound.enabled()) {
            return;
        }
        player.playSound(player.getLocation(), sound.name(), sound.volume(), sound.pitch());
    }

    private List<AfkZoneConfig.ChanceReward> rollChanceRewards(List<AfkZoneConfig.ChanceReward> rewards) {
        List<AfkZoneConfig.ChanceReward> awarded = new java.util.ArrayList<>();
        for (AfkZoneConfig.ChanceReward reward : rewards) {
            if (ThreadLocalRandom.current().nextDouble(100.0D) < reward.chancePercent()) {
                awarded.add(reward);
            }
        }
        return List.copyOf(awarded);
    }

    private void runCommands(List<String> commands, Map<String, String> placeholders) {
        for (String raw : commands) {
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

    private Map<String, String> placeholders(Player player,
                                             AfkSession session,
                                             AfkZoneConfig.RankProfile profile,
                                             List<AfkZoneConfig.ChanceReward> awarded) {
        Map<String, String> values = profilePlaceholders(player, profile);
        AfkZoneConfig.BaseReward baseReward = configSupplier.get().rewards().base();
        values.put("uuid", player.getUniqueId().toString());
        values.put("time", formatElapsed(elapsedSeconds(session)));
        values.put("elapsed_seconds", Long.toString(elapsedSeconds(session)));
        values.put("interval", formatElapsed(profile.rewardIntervalSeconds()));
        values.put("interval_seconds", Long.toString(profile.rewardIntervalSeconds()));
        values.put("next_reward_in", formatElapsed(nextRewardInSeconds(session)));
        values.put("group", profile.id());
        values.put("reward_group", profile.id());
        values.put("base_reward", baseReward.displayName());
        values.put("base_amount", Integer.toString(baseReward.amount()));
        values.put("bonus_rewards", formatBonusRewards(awarded));
        values.put("reward_name", awarded.isEmpty() ? baseReward.displayName() : awarded.getFirst().displayName());
        values.put("reward_names", rewardNames(baseReward, awarded));
        values.put("amount", awarded.isEmpty() ? Integer.toString(baseReward.amount()) : Integer.toString(awarded.getFirst().amount()));
        return values;
    }

    private Map<String, String> profilePlaceholders(Player player, AfkZoneConfig.RankProfile profile) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("profile", profile.id());
        values.put("profile_name", profile.displayName());
        values.put("color", profile.color());
        return values;
    }

    private String formatBonusRewards(List<AfkZoneConfig.ChanceReward> awarded) {
        if (awarded.isEmpty()) {
            return "";
        }
        return " + " + String.join(", ", awarded.stream()
                .map(reward -> reward.displayName() + " x" + reward.amount())
                .toList());
    }

    private String rewardNames(AfkZoneConfig.BaseReward baseReward, List<AfkZoneConfig.ChanceReward> awarded) {
        List<String> names = new java.util.ArrayList<>();
        names.add(baseReward.displayName());
        names.addAll(awarded.stream().map(AfkZoneConfig.ChanceReward::displayName).toList());
        return String.join(", ", names);
    }

    private Optional<AfkZoneConfig.RankProfile> profile(String id) {
        return configSupplier.get().rankProfiles().stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst();
    }

    private AfkZoneConfig.RankProfile operatorProfile(AfkZoneConfig config) {
        return config.rankProfiles().stream()
                .filter(AfkZoneConfig.RankProfile::operatorAccess)
                .findFirst()
                .orElseGet(() -> fallbackProfile(config));
    }

    private AfkZoneConfig.RankProfile fallbackProfile(AfkZoneConfig config) {
        return config.rankProfiles().stream()
                .filter(AfkZoneConfig.RankProfile::fallbackAccess)
                .findFirst()
                .orElse(config.rankProfiles().getLast());
    }

    private boolean hasAnyPermission(Player player, List<String> permissions) {
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private long elapsedSeconds(AfkSession session) {
        return Math.max(0L, Duration.between(session.enteredAt(), clock.instant()).toSeconds());
    }

    private long nextRewardInSeconds(AfkSession session) {
        return Math.max(0L, Duration.between(clock.instant(), session.nextRewardAt()).toSeconds());
    }

    private String formatElapsed(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
