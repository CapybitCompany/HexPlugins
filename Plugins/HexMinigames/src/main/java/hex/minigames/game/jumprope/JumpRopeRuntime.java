package hex.minigames.game.jumprope;

import java.util.*;

/** Completion, delayed respawn and collision cooldowns without scheduler tasks. */
public final class JumpRopeRuntime {
    private final Set<UUID> finished = new HashSet<>();
    private final Map<UUID, Long> respawns = new HashMap<>();
    private final Map<UUID, Long> nextHit = new HashMap<>();

    public boolean finish(UUID id) {
        return !respawns.containsKey(id) && finished.add(id);
    }
    public boolean finished(UUID id) { return finished.contains(id); }
    public boolean beginFall(UUID id) {
        if (finished.contains(id) || respawns.containsKey(id)) return false;
        respawns.put(id, Long.MAX_VALUE);
        nextHit.remove(id);
        return true;
    }
    public void arrived(UUID id, long tick, int delayTicks) {
        if (respawns.containsKey(id)) respawns.put(id, tick + delayTicks);
    }
    public boolean resume(UUID id, long tick) {
        Long deadline = respawns.get(id);
        if (deadline == null || tick < deadline) return false;
        respawns.remove(id);
        return true;
    }
    public long delayRemaining(UUID id, long tick, int fullDelay) {
        long deadline = respawns.getOrDefault(id, tick);
        return deadline == Long.MAX_VALUE ? fullDelay : Math.max(0, deadline - tick);
    }
    public boolean canHit(UUID id, long tick, int cooldown) {
        if (finished.contains(id) || respawns.containsKey(id) || tick < nextHit.getOrDefault(id, 0L)) return false;
        nextHit.put(id, tick + cooldown);
        return true;
    }
    public void remove(UUID id) { respawns.remove(id); nextHit.remove(id); finished.remove(id); }
}
