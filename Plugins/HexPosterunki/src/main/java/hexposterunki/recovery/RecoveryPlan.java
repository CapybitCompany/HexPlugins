package hexposterunki.recovery;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What recovery must do to make the world match the persisted run again.
 *
 * @param removeEntities   duplicates, stale-run leftovers and wrong-wave mobs
 * @param spawnMissingMobs how many mobs of the current wave have to be spawned again
 * @param bossAction       how to handle the boss
 * @param reattachBossId   entity to re-bind when {@code bossAction} is {@link BossAction#REATTACH}
 */
public record RecoveryPlan(List<UUID> removeEntities, int spawnMissingMobs,
                           BossAction bossAction, UUID reattachBossId) {

    public RecoveryPlan {
        removeEntities = removeEntities == null ? List.of() : List.copyOf(removeEntities);
        spawnMissingMobs = Math.max(0, spawnMissingMobs);
        bossAction = Objects.requireNonNullElse(bossAction, BossAction.NONE);
    }

    public boolean isNoop() {
        return removeEntities.isEmpty() && spawnMissingMobs == 0 && bossAction == BossAction.NONE;
    }

    public enum BossAction {
        /** Nothing to do - no boss, boss already dead, or the respawn budget is spent. */
        NONE,
        /** The stored boss entity is still alive; just bind it to the run again. */
        REATTACH,
        /** The stored boss entity is gone; recreate it once with the same stored boss id. */
        SPAWN
    }
}
