package hexposterunki.engine;

import java.util.Objects;
import java.util.UUID;

/**
 * The single place that decides whether a player counts as a participant.
 *
 * <p>Pure on purpose: damage handling and kill crediting both call it, so they can no longer drift
 * apart, and the whole rule set is unit-testable. The previous version checked only town
 * membership when crediting a kill, which let a member shoot into the region from outside and
 * still collect kills.
 */
public final class ParticipationRules {

    private ParticipationRules() {
    }

    /**
     * @param combatPhase     true while the run is in a phase that carries fighting
     * @param hasOutpost      true when an active outpost definition exists
     * @param online          player is connected
     * @param alive           player is not dead
     * @param spectator       player is in spectator mode
     * @param insideRegion    player's current location is inside the event region
     * @param tracked         engine has the player registered as present
     * @param playerTown      the player's town, or null
     * @param controllingTown the town currently holding the outpost, or null when unclaimed
     */
    public static Participation evaluate(boolean combatPhase, boolean hasOutpost, boolean online,
                                         boolean alive, boolean spectator, boolean insideRegion,
                                         boolean tracked, UUID playerTown, UUID controllingTown) {
        if (!combatPhase || !hasOutpost) {
            return Participation.NO_ACTIVE_RUN;
        }
        if (!online || !alive || spectator) {
            return Participation.NOT_ALIVE;
        }
        if (!insideRegion || !tracked) {
            return Participation.OUTSIDE_REGION;
        }
        if (playerTown == null) {
            return Participation.NO_TOWN;
        }
        if (controllingTown != null && !Objects.equals(controllingTown, playerTown)) {
            return Participation.WRONG_TOWN;
        }
        return Participation.ELIGIBLE;
    }

    /**
     * Operating-state gate in front of {@link #evaluate}.
     *
     * @param mode        the engine's current operating mode
     * @param combatPhase true while the run is in a phase that carries fighting
     * @return {@link Participation#SUSPENDED} while a fight exists but may not progress, otherwise
     * null so the caller continues with {@link #evaluate}
     */
    public static Participation gate(EngineMode mode, boolean combatPhase) {
        if (mode == null || mode.allowsProgress()) {
            return null;
        }
        return combatPhase ? Participation.SUSPENDED : Participation.NO_ACTIVE_RUN;
    }

    /**
     * Applies an admin bypass on top of a refusal.
     *
     * <p>A bypass only unlocks damage. It deliberately never turns into {@link Participation#ELIGIBLE},
     * so an operator testing the event cannot accidentally create ranking positions or reward claims.
     * A suspended encounter stays suspended: its results could not be stored.
     */
    public static Participation withBypass(Participation evaluated, boolean bypass) {
        if (evaluated == Participation.ELIGIBLE || evaluated == Participation.SUSPENDED || !bypass) {
            return evaluated;
        }
        return Participation.ADMIN_BYPASS;
    }
}
