package hex.minigames.game.breezetower;

import java.util.*;

/** Tick-based survival scoring and staggered firing, independent of the server. */
public final class BreezeTowerRuntime {
    private final Map<UUID, Long> eliminatedAt = new HashMap<>();
    private final int interval;
    private final int firstDelay;
    private final int shooterCount;
    private final Random random;
    private long nextShotTick;
    private long startTick;
    private int nextShooter;
    private UUID previousTarget;

    public BreezeTowerRuntime(int interval, int firstDelay, int shooterCount, Random random) {
        this.interval = Math.max(1, interval);
        this.firstDelay = Math.max(1, firstDelay);
        this.shooterCount = shooterCount;
        this.random = random;
    }

    public void start(long tick) {
        startTick = tick;
        nextShotTick = tick + firstDelay;
        nextShooter = 0;
        previousTarget = null;
        eliminatedAt.clear();
    }

    /** At most one shot per tick; missed ticks never cause a simultaneous volley. */
    public Optional<Shot> shot(long tick, List<UUID> active) {
        if (tick < nextShotTick || shooterCount == 0 || tick - startTick >= 1200) return Optional.empty();
        List<UUID> targets = active.stream().filter(id -> !eliminatedAt.containsKey(id)).toList();
        List<UUID> candidates = targets.size() > 1 ? targets.stream().filter(id -> !id.equals(previousTarget)).toList() : targets;
        UUID target = candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
        Shot shot = new Shot(nextShooter, target);
        nextShooter = (nextShooter + 1) % shooterCount;
        previousTarget = target;
        nextShotTick = tick + interval;
        return Optional.of(shot);
    }

    /** Most volleys contain one charge; one in five also uses the next, distinct NPC. */
    public List<Shot> shots(long tick, List<UUID> active) {
        Optional<Shot> first = shot(tick, active);
        if (first.isEmpty()) return List.of();
        if (shooterCount < 2 || random.nextInt(5) != 0) return List.of(first.get());
        List<UUID> targets = active.stream().filter(id -> !eliminatedAt.containsKey(id)).toList();
        UUID target = targets.isEmpty() ? null : targets.get(random.nextInt(targets.size()));
        Shot extra = new Shot(nextShooter, target);
        nextShooter = (nextShooter + 1) % shooterCount;
        return List.of(first.get(), extra);
    }

    public boolean eliminate(UUID player, long tick) {
        return eliminatedAt.putIfAbsent(player, Math.max(0, tick - startTick)) == null;
    }

    public long survivedTicks(UUID player, long tick) {
        return Math.min(1200, eliminatedAt.getOrDefault(player, Math.max(0, tick - startTick)));
    }

    public int points(UUID player, long tick) {
        return (int) (survivedTicks(player, tick) / 400);
    }

    public record Shot(int shooterIndex, UUID target) { }
}
