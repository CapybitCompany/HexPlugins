package hex.minigames.game.glassbridge;

import hex.minigames.model.BlockPosition;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class GlassBridgeRuntime {
    private final GlassBridgeConfig config;
    private final Map<Integer, Side> safeSides = new LinkedHashMap<>();
    private final Map<UUID, Integer> maxProgressMeters = new LinkedHashMap<>();
    private final Map<UUID, Double> preciseProgress = new LinkedHashMap<>();
    private final Map<UUID, Boolean> finished = new LinkedHashMap<>();
    private final Map<UUID, Boolean> fallen = new LinkedHashMap<>();
    private final Map<Integer, EnumMap<Side, Boolean>> destroyed = new LinkedHashMap<>();

    public GlassBridgeRuntime(GlassBridgeConfig config, Collection<UUID> participants, Random random) {
        this.config = config;
        Random rng = random == null ? new Random() : random;
        for (GlassBridgeConfig.Pair pair : config.pairs()) {
            safeSides.put(pair.index(), rng.nextBoolean() ? Side.LEFT : Side.RIGHT);
            destroyed.put(pair.index(), new EnumMap<>(Side.class));
        }
        for (UUID participant : participants) {
            maxProgressMeters.put(participant, 0);
            finished.put(participant, false);
            fallen.put(participant, false);
        }
    }

    public int pairCount() {
        return config.pairs().size();
    }

    public Side safeSide(int pairIndex) {
        return safeSides.get(pairIndex);
    }

    public LandingResult land(UUID playerId, BlockPosition blockUnderFeet) {
        if (Boolean.TRUE.equals(finished.get(playerId)) || Boolean.TRUE.equals(fallen.get(playerId))) {
            return LandingResult.none();
        }
        PlatformHit hit = platformAt(blockUnderFeet);
        if (hit == null) return LandingResult.none();
        if (safeSides.get(hit.pair.index()) == hit.side) return new LandingResult(LandingOutcome.SAFE, hit.pair.index(), hit.side, null);
        EnumMap<Side, Boolean> pairDestroyed = destroyed.get(hit.pair.index());
        if (pairDestroyed.getOrDefault(hit.side, false)) return new LandingResult(LandingOutcome.BAD_ALREADY_BROKEN, hit.pair.index(), hit.side, hit.platform);
        pairDestroyed.put(hit.side, true);
        return new LandingResult(LandingOutcome.BAD_BROKEN, hit.pair.index(), hit.side, hit.platform);
    }

    public int updateProgress(UUID playerId, double z) {
        if (Boolean.TRUE.equals(finished.get(playerId)) || Boolean.TRUE.equals(fallen.get(playerId))) {
            return maxProgressMeters(playerId);
        }
        int meters = metersFromZ(z);
        preciseProgress.merge(playerId, Math.max(0.0, config.progressOriginZ() - z), Math::max);
        maxProgressMeters.compute(playerId, (ignored, current) -> Math.max(current == null ? 0 : current, meters));
        return maxProgressMeters(playerId);
    }

    public int metersFromZ(double z) {
        return Math.max(0, (int) Math.floor(config.progressOriginZ() - z));
    }

    /** Records forward distance only above the bridge corridor, including gaps between panes. */
    public double updateProgress(UUID playerId, double x, double y, double z) {
        int minX = config.pairs().stream().mapToInt(p -> p.left().region().minX()).min().orElse(0);
        int maxX = config.pairs().stream().mapToInt(p -> p.right().region().maxX()).max().orElse(0);
        int deckY = config.pairs().get(0).left().region().maxY();
        double endZ = config.finishRegion().minZ();
        if (x >= minX && x < maxX + 1.0 && y >= deckY + 1.0
                && z >= endZ && z <= config.progressOriginZ() + 1.0) {
            updateProgress(playerId, z);
        }
        return preciseProgressMeters(playerId);
    }

    public double preciseProgressMeters(UUID playerId) {
        return preciseProgress.getOrDefault(playerId, 0.0);
    }

    /** Ends the fall penalty while preserving the best distance and broken panes. */
    public void respawn(UUID playerId) {
        if (fallen.containsKey(playerId)) fallen.put(playerId, false);
    }

    public boolean finish(UUID playerId) {
        if (Boolean.TRUE.equals(finished.get(playerId)) || Boolean.TRUE.equals(fallen.get(playerId))) return false;
        finished.put(playerId, true);
        return true;
    }

    public int score(UUID playerId) {
        return Math.min(3, maxProgressMeters(playerId) / 20)
                + (Boolean.TRUE.equals(finished.get(playerId)) ? config.finishBonus() : 0);
    }

    public int maxProgressMeters(UUID playerId) {
        return maxProgressMeters.getOrDefault(playerId, 0);
    }

    public boolean eliminated(UUID playerId) {
        return fallen.getOrDefault(playerId, false);
    }

    public boolean eliminate(UUID playerId) {
        return markFall(playerId);
    }

    public boolean markFall(UUID playerId) {
        if (Boolean.TRUE.equals(finished.get(playerId)) || Boolean.TRUE.equals(fallen.get(playerId))) return false;
        fallen.put(playerId, true);
        return true;
    }

    public boolean fallTriggered(UUID playerId) {
        return fallen.getOrDefault(playerId, false);
    }

    public boolean triggerFall(UUID playerId, double y) {
        if (y > config.fallY()) return false;
        return markFall(playerId);
    }

    public boolean inFinishedOrEliminatedState(UUID playerId) {
        return Boolean.TRUE.equals(finished.get(playerId)) || Boolean.TRUE.equals(fallen.get(playerId));
    }

    public boolean finished(UUID playerId) {
        return finished.getOrDefault(playerId, false);
    }

    public PlatformHit platformAt(BlockPosition block) {
        if (block == null) return null;
        for (GlassBridgeConfig.Pair pair : config.pairs()) {
            if (pair.left().region().contains(block)) return new PlatformHit(pair, Side.LEFT, pair.left());
            if (pair.right().region().contains(block)) return new PlatformHit(pair, Side.RIGHT, pair.right());
        }
        return null;
    }

    public enum Side {
        LEFT,
        RIGHT
    }

    public enum LandingOutcome {
        NONE,
        SAFE,
        BAD_BROKEN,
        BAD_ALREADY_BROKEN
    }

    public record LandingResult(LandingOutcome outcome, int pairIndex, Side side, GlassBridgeConfig.Platform platform) {
        static LandingResult none() {
            return new LandingResult(LandingOutcome.NONE, 0, null, null);
        }
    }

    public record PlatformHit(GlassBridgeConfig.Pair pair, Side side, GlassBridgeConfig.Platform platform) {
    }
}
