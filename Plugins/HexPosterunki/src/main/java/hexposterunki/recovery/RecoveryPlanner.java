package hexposterunki.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure reconciliation between the persisted run and the entities actually present in the world.
 *
 * <p>Guarantees the recovery requirements depend on:
 * <ul>
 *   <li>entities of a different run, or of a different wave, are removed;</li>
 *   <li>surplus mobs beyond the persisted live count are removed, so a restart never doubles a wave;</li>
 *   <li>a living stored boss is re-attached, never spawned again - and re-attaching deliberately
 *       consumes no part of the respawn budget;</li>
 *   <li>a missing boss is recreated at most once, always with the same stored boss id.</li>
 * </ul>
 */
public final class RecoveryPlanner {

    private RecoveryPlanner() {
    }

    public static RecoveryPlan plan(RecoveryInput input) {
        Objects.requireNonNull(input, "input");

        List<UUID> remove = new ArrayList<>();
        List<FoundEntity> ownMobs = new ArrayList<>();
        List<FoundEntity> ownBosses = new ArrayList<>();

        for (FoundEntity entity : input.found()) {
            if (entity == null) {
                continue;
            }
            if (input.runId() == null || !input.runId().equals(entity.runId())) {
                // Leftover from an earlier run (or an untracked run) - never counts, always removed.
                remove.add(entity.entityId());
                continue;
            }
            if (entity.boss()) {
                ownBosses.add(entity);
            } else {
                ownMobs.add(entity);
            }
        }

        if (!input.combatPhase()) {
            // The persisted phase must not hold any encounter entity at all.
            ownMobs.forEach(entity -> remove.add(entity.entityId()));
            ownBosses.forEach(entity -> remove.add(entity.entityId()));
            return new RecoveryPlan(remove, 0, RecoveryPlan.BossAction.NONE, null);
        }

        // --- mobs of the current wave -------------------------------------------------
        List<FoundEntity> currentWave = new ArrayList<>();
        for (FoundEntity mob : ownMobs) {
            if (mob.wave() == input.currentWave()) {
                currentWave.add(mob);
            } else {
                remove.add(mob.entityId());
            }
        }
        // Stable order so the same restart always keeps the same entities.
        currentWave.sort(Comparator.comparing(entity -> entity.entityId().toString()));

        int keep = Math.min(input.expectedAliveMobs(), currentWave.size());
        for (int i = keep; i < currentWave.size(); i++) {
            remove.add(currentWave.get(i).entityId());
        }
        int missing = input.expectedAliveMobs() - keep;

        // --- boss ---------------------------------------------------------------------
        RecoveryPlan.BossAction bossAction = RecoveryPlan.BossAction.NONE;
        UUID reattach = null;

        // MISSING counts too: the entity vanished without a death event, so recovery may still
        // look for it and, if the budget allows, recreate it. Only DEAD is final.
        boolean bossWanted = input.bossPhase()
                && input.bossId() != null
                && input.bossStatus().wantsEntity();

        FoundEntity stored = null;
        if (input.storedBossEntityId() != null) {
            for (FoundEntity boss : ownBosses) {
                if (input.storedBossEntityId().equals(boss.entityId())) {
                    stored = boss;
                    break;
                }
            }
        }

        for (FoundEntity boss : ownBosses) {
            boolean isStored = stored != null && stored.entityId().equals(boss.entityId());
            if (!isStored || !bossWanted) {
                // Any boss that is not the one we recorded is a duplicate and must go.
                remove.add(boss.entityId());
            }
        }

        if (bossWanted) {
            if (stored != null) {
                bossAction = RecoveryPlan.BossAction.REATTACH;
                reattach = stored.entityId();
            } else if (input.bossSpawnAttempts() < input.maxBossSpawns()) {
                bossAction = RecoveryPlan.BossAction.SPAWN;
            }
        }

        return new RecoveryPlan(remove, missing, bossAction, reattach);
    }
}
