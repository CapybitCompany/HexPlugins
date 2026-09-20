package hex.minigames.game.popcorn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Horizontal displacement resets the idle timer; looking around and jumping in place do not. */
public final class PopcornMovementTracker {
    private final Map<UUID, Sample> samples = new HashMap<>();

    public long idleTicks(UUID id, double x, double z, long tick) {
        Sample old = samples.get(id);
        if (old == null || Math.hypot(x - old.x(), z - old.z()) >= 0.2) {
            samples.put(id, new Sample(x, z, tick));
            return 0;
        }
        return Math.max(0, tick - old.tick());
    }

    public void remove(UUID id) { samples.remove(id); }
    public void clear() { samples.clear(); }

    private record Sample(double x, double z, long tick) { }
}
