package hex.minigames.game.sumo;
import java.util.*;

/** Accumulates only time inside the sumo disc, preserving it across respawns. */
public final class SumoRuntime {
    private final Map<UUID, Long> ticks = new HashMap<>();
    public static boolean onArena(double x, double y, double z) {
        return Math.hypot(x - 438.5, z - 349.5) <= 9.0 && y >= 47.9 && y <= 50.5;
    }
    public void tick(UUID id, boolean onArena) { if (onArena) ticks.merge(id, 1L, Long::sum); }
    public long ticks(UUID id) { return ticks.getOrDefault(id, 0L); }
    /** Seconds and hundredths, advancing five hundredths per server tick. */
    public static String formatTime(long ticks) {
        return String.format(Locale.ROOT, "%02d:%02d", ticks / 20, ticks % 20 * 5);
    }
    public int points(UUID id) { return (int) Math.min(3, ticks(id) / 400); }
}
