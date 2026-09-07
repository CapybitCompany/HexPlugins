package hex.parkour.service;

import hex.parkour.config.FinishRewardConfig;
import hex.parkour.config.ParkourConfig;
import hex.parkour.model.ParkourArena;
import hex.parkour.persistence.ParkourTimesRepository;
import hex.parkour.util.DurationFormats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

public final class FinishRewardService {
    private final Plugin plugin;
    private final ParkourTimesRepository times;

    public FinishRewardService(Plugin plugin, ParkourTimesRepository times) {
        this.plugin = plugin;
        this.times = times;
    }

    public GrantResult grantIfEligible(Player player, ParkourArena arena, long timeMillis, ParkourConfig config) {
        FinishRewardConfig finishReward = config.finishReward();
        if (finishReward == null || !finishReward.enabled()) return GrantResult.notQualified();
        FinishRewardConfig.ArenaReward reward = finishReward.rewardFor(arena.id());
        if (reward == null || !reward.qualifies(timeMillis)) return GrantResult.notQualified();

        boolean claimed;
        try {
            claimed = times.markFinishRewardClaimed(player.getUniqueId(), player.getName(), arena.id());
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not persist HexParkour finish reward claim for "
                    + player.getName() + "/" + arena.id() + ": " + rootMessage(error));
            return GrantResult.storageFailed(reward.amount());
        }
        if (!claimed) {
            boolean alreadyClaimed = times.find(player.getUniqueId(), arena.id())
                    .map(record -> record.finishRewardClaimed())
                    .orElse(false);
            return alreadyClaimed ? GrantResult.alreadyClaimed(reward.amount()) : GrantResult.storageFailed(reward.amount());
        }

        String command = command(finishReward.command(), player, arena, reward.amount(), timeMillis, config.timerFormat());
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        if (!dispatched) {
            plugin.getLogger().warning("HexParkour finish reward command was not handled after claim persisted: " + command);
            return GrantResult.dispatchFailed(reward.amount());
        }
        return GrantResult.dispatched(reward.amount());
    }

    private String command(String raw, Player player, ParkourArena arena, int amount, long timeMillis, String timerFormat) {
        String playerName = safePlayerName(player.getName());
        String template = raw == null || raw.isBlank() ? "dajpunkt global {player} {amount}" : raw;
        return template
                .replace("{player}", playerName)
                .replace("{amount}", Integer.toString(amount))
                .replace("{arena}", safeArenaId(arena.id()))
                .replace("{time}", DurationFormats.formatMillis(timeMillis, timerFormat));
    }

    private static String safePlayerName(String playerName) {
        String safe = playerName == null ? "" : playerName.replaceAll("[^A-Za-z0-9_]", "");
        return safe.isBlank() ? "-" : safe;
    }

    private static String safeArenaId(String arenaId) {
        String safe = arenaId == null ? "" : arenaId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record GrantResult(Status status, int amount) {
        public static GrantResult notQualified() {
            return new GrantResult(Status.NOT_QUALIFIED, 0);
        }

        public static GrantResult alreadyClaimed(int amount) {
            return new GrantResult(Status.ALREADY_CLAIMED, amount);
        }

        public static GrantResult storageFailed(int amount) {
            return new GrantResult(Status.STORAGE_FAILED, amount);
        }

        public static GrantResult dispatchFailed(int amount) {
            return new GrantResult(Status.DISPATCH_FAILED, amount);
        }

        public static GrantResult dispatched(int amount) {
            return new GrantResult(Status.DISPATCHED, amount);
        }
    }

    public enum Status {
        NOT_QUALIFIED,
        ALREADY_CLAIMED,
        STORAGE_FAILED,
        DISPATCH_FAILED,
        DISPATCHED
    }
}
