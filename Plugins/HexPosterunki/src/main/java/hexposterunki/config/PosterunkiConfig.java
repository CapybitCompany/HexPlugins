package hexposterunki.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Root logic configuration. Contains only parameters - every player-visible string lives in
 * HexCore UI templates under the {@code posterunki} namespace.
 */
public record PosterunkiConfig(
        boolean enabled,
        boolean debug,
        Timing timing,
        int requiredKills,
        boolean avoidImmediateRepeat,
        List<WaveDefinition> waves,
        BossConfig boss,
        Towns towns,
        Protection protection,
        Ui ui,
        RewardsConfig rewards,
        LootConfig loot
) {

    public PosterunkiConfig {
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(boss, "boss");
        Objects.requireNonNull(towns, "towns");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(rewards, "rewards");
        Objects.requireNonNull(loot, "loot");
        requiredKills = Math.max(0, requiredKills);
        waves = waves == null ? List.of() : List.copyOf(waves);
    }

    /** Total mobs the configured waves are expected to spawn, used by validation. */
    public int waveCount() {
        return waves.size();
    }

    /** @return the wave with this 1-based number, or null when it is out of range. */
    public WaveDefinition wave(int waveNumber) {
        int index = waveNumber - 1;
        if (index < 0 || index >= waves.size()) {
            return null;
        }
        return waves.get(index);
    }

    public record Timing(
            long activeWindowSeconds,
            long cooldownSeconds,
            long preparationSeconds,
            long waveDelaySeconds,
            long gracePeriodSeconds,
            long lootSeconds,
            long tickIntervalTicks,
            long snapshotIntervalSeconds
    ) {
        public Timing {
            activeWindowSeconds = Math.max(0L, activeWindowSeconds);
            cooldownSeconds = Math.max(0L, cooldownSeconds);
            preparationSeconds = Math.max(0L, preparationSeconds);
            waveDelaySeconds = Math.max(0L, waveDelaySeconds);
            gracePeriodSeconds = Math.max(1L, gracePeriodSeconds);
            lootSeconds = Math.max(0L, lootSeconds);
            tickIntervalTicks = Math.max(1L, tickIntervalTicks);
            snapshotIntervalSeconds = Math.max(5L, snapshotIntervalSeconds);
        }

        public long activeWindowMillis() {
            return activeWindowSeconds * 1000L;
        }

        public long cooldownMillis() {
            return cooldownSeconds * 1000L;
        }

        public long gracePeriodMillis() {
            return gracePeriodSeconds * 1000L;
        }

        /** Length of the post-victory loot window. */
        public long lootMillis() {
            return lootSeconds * 1000L;
        }
    }

    /**
     * @param snapshotMaterials materials whose block state is captured at run start and restored on
     *                          reset (doors, trapdoors, gates, buttons, levers, plates, ...)
     */
    /**
     * HexTowns binding. HexTowns is not part of this build graph, so the adapter resolves the
     * published {@code TownsApi} service at runtime and checks it against these expectations.
     *
     * @param requiredVersion exact HexTowns plugin version, or {@code "*"} to accept any version
     *                        whose API shape still matches
     */
    public record Towns(String requiredVersion) {
        public Towns {
            requiredVersion = requiredVersion == null || requiredVersion.isBlank() ? "*" : requiredVersion.trim();
        }

        public boolean acceptsAnyVersion() {
            return "*".equals(requiredVersion);
        }
    }

    /**
     * @param snapshotMaterials    materials whose block state is captured at run start and restored
     *                             on reset (doors, trapdoors, gates, buttons, levers, plates, ...)
     * @param maxResetScanVolume   cap for the reset-time fire sweep, mirroring the capture cap
     * @param scanBlocksPerTick    how many blocks a region scan may touch per tick on the main thread
     * @param removeGroundItems    whether leftover ground items are removed on reset; off by default
     *                             so player death drops and unrelated items survive
     */
    public record Protection(
            long maxSnapshotVolume,
            long maxResetScanVolume,
            long scanBlocksPerTick,
            int chunkTicketLimit,
            long containmentCheckIntervalTicks,
            boolean removeGroundItems,
            Set<String> snapshotMaterials
    ) {
        public Protection {
            maxSnapshotVolume = Math.max(0L, maxSnapshotVolume);
            maxResetScanVolume = Math.max(0L, maxResetScanVolume);
            scanBlocksPerTick = Math.max(256L, scanBlocksPerTick);
            chunkTicketLimit = Math.max(0, chunkTicketLimit);
            containmentCheckIntervalTicks = Math.max(5L, containmentCheckIntervalTicks);
            snapshotMaterials = snapshotMaterials == null ? Set.of() : Set.copyOf(snapshotMaterials);
        }
    }

    public record Ui(
            boolean bossbarEnabled,
            double bossbarMaxDistance,
            boolean hologramEnabled,
            float hologramViewRange,
            long hologramUpdateIntervalTicks,
            boolean actionbarEnabled
    ) {
        public Ui {
            bossbarMaxDistance = Math.max(0.0D, bossbarMaxDistance);
            hologramViewRange = Math.max(0.1F, hologramViewRange);
            hologramUpdateIntervalTicks = Math.max(5L, hologramUpdateIntervalTicks);
        }

        public double bossbarMaxDistanceSquared() {
            return bossbarMaxDistance * bossbarMaxDistance;
        }
    }
}
