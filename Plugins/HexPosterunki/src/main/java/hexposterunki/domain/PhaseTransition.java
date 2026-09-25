package hexposterunki.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for legal phase moves. Kept free of Bukkit so the whole table is
 * unit-testable and so illegal engine transitions fail loudly instead of corrupting a run.
 */
public final class PhaseTransition {

    private static final Map<RunPhase, Set<RunPhase>> ALLOWED = build();

    private PhaseTransition() {
    }

    private static Map<RunPhase, Set<RunPhase>> build() {
        Map<RunPhase, Set<RunPhase>> map = new EnumMap<>(RunPhase.class);

        // Startup reconciliation may restore any persisted phase.
        map.put(RunPhase.RECOVERING, EnumSet.of(
                RunPhase.WAITING, RunPhase.PREPARATION, RunPhase.WAVES, RunPhase.BOSS,
                RunPhase.COMPLETED, RunPhase.LOOTING, RunPhase.RESETTING, RunPhase.COOLDOWN,
                RunPhase.FAILED));

        // Announced but unclaimed: a town claims it, or the active window runs out.
        map.put(RunPhase.WAITING, EnumSet.of(
                RunPhase.PREPARATION, RunPhase.RESETTING, RunPhase.COOLDOWN, RunPhase.FAILED));

        // Claimed: waves start, the region is wiped back to WAITING, or an admin stops it.
        map.put(RunPhase.PREPARATION, EnumSet.of(
                RunPhase.WAVES, RunPhase.WAITING, RunPhase.RESETTING, RunPhase.COOLDOWN, RunPhase.FAILED));

        // Waves: boss phase, direct victory when no boss was rolled, wipe, or admin stop.
        map.put(RunPhase.WAVES, EnumSet.of(
                RunPhase.BOSS, RunPhase.COMPLETED, RunPhase.WAITING, RunPhase.RESETTING, RunPhase.COOLDOWN,
                RunPhase.FAILED));

        // Boss: victory, wipe back to wave 1, or admin stop.
        map.put(RunPhase.BOSS, EnumSet.of(
                RunPhase.COMPLETED, RunPhase.WAITING, RunPhase.RESETTING, RunPhase.COOLDOWN, RunPhase.FAILED));

        // Victory is settled, then the winners get their loot window.
        map.put(RunPhase.COMPLETED, EnumSet.of(
                RunPhase.LOOTING, RunPhase.RESETTING, RunPhase.COOLDOWN, RunPhase.FAILED));

        // The loot window always ends in the reset and the server-wide cooldown.
        map.put(RunPhase.LOOTING, EnumSet.of(RunPhase.RESETTING, RunPhase.COOLDOWN, RunPhase.FAILED));

        // The cleanup only ever ends in the cooldown. Nothing leads from here back into a fight.
        map.put(RunPhase.RESETTING, EnumSet.of(RunPhase.COOLDOWN, RunPhase.FAILED));

        // Cooldown elapses and a new location is announced; an admin reset cleans up first.
        map.put(RunPhase.COOLDOWN, EnumSet.of(RunPhase.WAITING, RunPhase.RESETTING, RunPhase.FAILED));

        // A technical failure is only left through an explicit admin reset.
        map.put(RunPhase.FAILED, EnumSet.of(RunPhase.RESETTING, RunPhase.COOLDOWN));

        return Map.copyOf(map);
    }

    public static boolean allowed(RunPhase from, RunPhase to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<RunPhase> targets(RunPhase from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
