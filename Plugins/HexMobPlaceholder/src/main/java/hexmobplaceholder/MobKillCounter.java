package hexmobplaceholder;

import hexmobplaceholder.config.MobPlaceholderConfig;
import hexmobplaceholder.storage.MobBaselineStorage;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class MobKillCounter {

    private final MobBaselineStorage storage;
    private final Supplier<MobPlaceholderConfig> configSupplier;

    public MobKillCounter(MobBaselineStorage storage, Supplier<MobPlaceholderConfig> configSupplier) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public int totalKills(OfflinePlayer player) {
        Objects.requireNonNull(player, "player");
        MobPlaceholderConfig config = configSupplier.get();
        if (config == null) {
            return 0;
        }

        int total = 0;
        for (EntityType type : config.hostileMobs()) {
            int current = currentKills(player, type);
            int baseline = storage.baseline(player.getUniqueId(), type);
            total += Math.max(0, current - baseline);
        }
        return total;
    }

    public void resetProgress(OfflinePlayer player) {
        Objects.requireNonNull(player, "player");
        MobPlaceholderConfig config = configSupplier.get();
        if (config == null) {
            return;
        }

        for (EntityType type : config.hostileMobs()) {
            storage.setBaseline(player.getUniqueId(), type, currentKills(player, type));
        }
    }

    public Optional<TopPlayer> topPlayer(Iterable<? extends OfflinePlayer> players) {
        Objects.requireNonNull(players, "players");

        TopPlayer best = null;
        for (OfflinePlayer player : players) {
            String playerName = player.getName();
            if (playerName == null || playerName.isBlank()) {
                continue;
            }

            int kills = totalKills(player);
            if (kills <= 0) {
                continue;
            }

            if (best == null
                    || kills > best.kills()
                    || (kills == best.kills() && playerName.compareToIgnoreCase(best.playerName()) < 0)) {
                best = new TopPlayer(playerName, kills);
            }
        }
        return Optional.ofNullable(best);
    }

    private int currentKills(OfflinePlayer player, EntityType type) {
        try {
            return player.getStatistic(Statistic.KILL_ENTITY, type);
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    public record TopPlayer(String playerName, int kills) {
    }
}
