package hexposterunki.config;

import java.util.List;
import java.util.Objects;

/**
 * Boss phase configuration.
 *
 * @param spawnChance                 global probability that a run has a boss at all, rolled once
 * @param bosses                      weighted STORMBOSSY boss ids (low weight = rare/strong)
 * @param requireNativeSchedulesOff   refuse to operate while STORMBOSSY still schedules bosses itself
 * @param expectNativeRewards         warn when STORMBOSSY has no native rewards, since HexPosterunki
 *                                    deliberately never pays boss rewards itself
 */
public record BossConfig(
        boolean enabled,
        double spawnChance,
        String provider,
        String requiredVersion,
        boolean requireNativeSchedulesOff,
        boolean expectNativeRewards,
        List<WeightedBoss> bosses
) {

    public BossConfig {
        provider = provider == null || provider.isBlank() ? "stormbossy" : provider.trim();
        requiredVersion = requiredVersion == null || requiredVersion.isBlank() ? "1.0" : requiredVersion.trim();
        spawnChance = Math.max(0.0D, Math.min(1.0D, spawnChance));
        bosses = bosses == null ? List.of() : List.copyOf(bosses);
    }

    public boolean usable() {
        return enabled && spawnChance > 0.0D && !bosses.isEmpty();
    }

    public record WeightedBoss(String bossId, int weight) {
        public WeightedBoss {
            Objects.requireNonNull(bossId, "bossId");
            weight = Math.max(0, weight);
        }
    }
}
