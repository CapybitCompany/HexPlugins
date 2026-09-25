package hexposterunki.engine;

import hexposterunki.domain.RunPhase;

import java.util.Objects;

/**
 * Guard for callbacks that come back from the database after a delay.
 *
 * <p>A loot fill or a reset callback can land after the run already ended, after a reset started or
 * after a completely different fortress was selected. Applying it then would fill the chests of an
 * outpost that is no longer running. Every deferred main-thread callback re-checks this first.
 */
public final class RunGuard {

    private RunGuard() {
    }

    /**
     * @param expectedRunId  run the callback was scheduled for
     * @param currentRunId   run the engine is on right now
     * @param phase          current phase
     * @param resetInProgress true while the fortress is being cleaned up
     * @return true when the callback still refers to the live run and may be applied
     */
    public static boolean stillValid(String expectedRunId, String currentRunId, RunPhase phase,
                                     boolean resetInProgress) {
        if (expectedRunId == null || currentRunId == null) {
            return false;
        }
        if (!Objects.equals(expectedRunId, currentRunId)) {
            return false;
        }
        if (resetInProgress) {
            return false;
        }
        return phase != null && phase.isLive();
    }
}
