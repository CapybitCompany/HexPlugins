package hexposterunki.recovery;

import hexposterunki.boss.BossSpawnResult;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.config.WaveDefinition;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.engine.WaveSpawner;
import hexposterunki.persistence.AuditEvent;
import hexposterunki.persistence.OutpostDefinitionCodec;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.TrackedEntity;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Restores a run after a server or plugin restart.
 *
 * <p>Fixed, idempotent sequence: load the persisted state before any scheduler starts, refuse an
 * inconsistent state outright, take chunk tickets only for the active outpost, match world entities
 * against PDC tags and stored UUIDs, delete duplicates, top the current wave back up, and re-bind
 * or recreate the boss.
 *
 * <p>Re-binding an existing boss goes through {@link OutpostEngine#reattachBoss(UUID)} and does not
 * touch the respawn budget, so any number of restarts with a living boss leaves it intact.
 *
 * <p>The location comes from the geometry stored with the run, never from the current catalog: a
 * reload may have moved or removed the outpost while the run was active.
 *
 * <p>A run that was persisted in {@link RunPhase#RESETTING} was being cleaned up when the server
 * stopped. It is never resumed as a fight: its leftovers are removed and the interrupted cleanup is
 * finished, ending in the cooldown that was chosen when the reset began.
 */
public final class RecoveryService {

    private final OutpostEngine engine;
    private final Logger logger;

    public RecoveryService(OutpostEngine engine, Logger logger) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.logger = logger;
    }

    /** Main thread. Applies the loaded state to the engine and reconciles the world, fight included. */
    public void recover(PosterunkiRepository.LoadedState loaded) {
        recover(loaded, true);
    }

    /**
     * Main thread. Applies the loaded state to the engine: the run, its standings, its stored geometry
     * (pinned for protection) and - independent of the event operation - an interrupted cleanup.
     *
     * <p>Reconciling a fight can create opponents: a wave is topped up, a boss is spawned again or
     * re-attached. That part needs the event operation. When {@code combatAllowed} is false (for
     * example {@code enabled: false}, an empty catalog or an unavailable HexCustomMobs) a run in a
     * combat phase is loaded and protected but its world is left untouched, and the reconciliation is
     * reported as deferred; {@link #resumeDeferredCombat()} performs it once operation is released.
     *
     * @return true when the combat reconciliation was deferred
     */
    public boolean recover(PosterunkiRepository.LoadedState loaded, boolean combatAllowed) {
        // Holograms are non-persistent entities, but a hard crash can still leave one behind.
        int staleDisplays = engine.context().displays().removeStaleDisplays();
        if (staleDisplays > 0) {
            logger.info("[recovery] Usunięto pozostałe hologramy: " + staleDisplays);
        }

        if (loaded == null || loaded.global() == null) {
            engine.state().restoreEmpty();
            logger.info("[recovery] Brak zapisanego stanu - start od przerwy.");
            engine.persistState();
            return false;
        }
        // Every path below - including the ones that discard the stored run - continues above the
        // highest revision the database holds. Otherwise the database keeps rejecting new states as
        // stale while the writer reports success.
        engine.state().adoptRevisionFloor(loaded.persistedRevision());

        if (!loaded.consistent()) {
            // Pointer and run row disagree: resuming half a run is worse than starting clean.
            logger.severe("[recovery] Zapisany stan jest niespójny (wskaźnik i run nie pasują)."
                    + " Rozpoczynam od przerwy zamiast wznawiać uszkodzony run.");
            engine.setLastOutpostId(loaded.global().lastOutpostId());
            engine.state().restoreEmpty();
            engine.audit(AuditEvent.RECOVERY, "system", "niespojny-stan rewizja=" + loaded.persistedRevision());
            engine.persistState();
            return false;
        }
        if (loaded.run() == null) {
            engine.setLastOutpostId(loaded.global().lastOutpostId());
            engine.state().restoreEmpty();
            engine.persistState();
            return false;
        }

        RunSnapshot snapshot = loaded.run();
        engine.state().restore(snapshot);
        engine.setLastOutpostId(loaded.global().lastOutpostId() == null
                ? snapshot.outpostId() : loaded.global().lastOutpostId());
        engine.participation().ledger().load(loaded.participants());
        loaded.filledContainers().forEach(engine.context().loot()::markUnlocked);

        PosterunkiConfig config = engine.context().config().get();
        Optional<OutpostDefinition> configured = engine.context().catalog().get().find(snapshot.outpostId());
        Optional<OutpostDefinition> stored = storedDefinition(loaded, snapshot);

        if (snapshot.phase() == RunPhase.COOLDOWN) {
            // No location is held any more. Leftovers of the finished run are swept wherever its
            // geometry is known; nothing is reset or re-armed.
            Optional<OutpostDefinition> sweepArea = stored.or(() -> configured);
            int removed = sweepArea.map(area -> removeAll(RecoveryPlanner.plan(new RecoveryInput(snapshot.runId(),
                    snapshot.wave(), false, 0, scan(area, snapshot.runId()), false, snapshot.bossId(),
                    snapshot.bossStatus(), snapshot.bossEntityId(), snapshot.bossSpawnAttempts(),
                    OutpostEngine.MAX_BOSS_SPAWNS)).removeEntities())).orElse(0);
            engine.persistState();
            engine.audit(AuditEvent.RECOVERY, "system", "phase=COOLDOWN removed=" + removed);
            return false;
        }
        if (stored.isEmpty()) {
            recoverWithoutGeometry(snapshot, configured.isPresent());
            return false;
        }

        OutpostDefinition outpost = stored.get();
        if (configured.isEmpty()) {
            logger.warning("[recovery] Posterunek '" + outpost.id() + "' nie istnieje już w konfiguracji."
                    + " Run pozostaje w zapisanej lokalizacji do końca resetu.");
            engine.audit(AuditEvent.RECOVERY, "system", "zapisana-geometria usuniety-z-konfiguracji=" + outpost.id());
        } else if (!configured.get().equals(outpost)) {
            logger.warning("[recovery] Posterunek '" + outpost.id() + "' zmienił się w konfiguracji."
                    + " Run pozostaje w zapisanej lokalizacji do końca resetu.");
            engine.audit(AuditEvent.RECOVERY, "system", "zapisana-geometria zmieniony-w-konfiguracji=" + outpost.id());
        }
        // The run still owns its location (fight, loot window, failure or cleanup): pin exactly the
        // stored geometry so protection, loot checks and the reset keep using it until the reset ended.
        engine.bindActiveDefinition(outpost);
        engine.resumeDueLootFills(loaded.filledContainers());

        if (snapshot.phase() == RunPhase.RESETTING) {
            // A requested cleanup continues regardless of whether new events may start.
            recoverInterruptedReset(outpost, config, snapshot);
            return false;
        }
        if (!combatAllowed && snapshot.phase().hasCombat()) {
            logger.warning("[recovery] Run " + snapshot.runId() + " (" + snapshot.phase().polishLabel()
                    + ") został wczytany, a jego teren jest chroniony. Eventy są zablokowane, więc walka"
                    + " nie jest odtwarzana - moby i boss zostaną uzgodnione po odblokowaniu.");
            engine.audit(AuditEvent.RECOVERY, "system", "ochrona-bez-walki phase=" + snapshot.phase()
                    + " wave=" + snapshot.wave());
            return true;
        }
        reconcileWorld(outpost, config, snapshot, "start");
        return false;
    }

    /**
     * Performs the combat reconciliation that {@link #recover(PosterunkiRepository.LoadedState, boolean)}
     * deferred. Runs against the engine's current state, so a run that was reset or failed meanwhile is
     * left alone.
     *
     * @return true when a fight was reconciled
     */
    public boolean resumeDeferredCombat() {
        RunSnapshot snapshot = engine.state().snapshot();
        Optional<OutpostDefinition> outpost = engine.activeOutpost();
        if (!snapshot.phase().hasCombat() || outpost.isEmpty() || engine.resetInProgress()) {
            return false;
        }
        reconcileWorld(outpost.get(), engine.context().config().get(), snapshot, "po-odblokowaniu");
        return true;
    }

    /** Makes the world match a stored run: entities, the current wave, the boss, chunk tickets. */
    private void reconcileWorld(OutpostDefinition outpost, PosterunkiConfig config, RunSnapshot snapshot,
                                String occasion) {
        if (snapshot.phase().isLive()) {
            engine.context().chunkTickets().acquire(outpost, config.protection().chunkTicketLimit());
            warnIfSpawnAreaUncovered(outpost, config);
        }

        List<FoundEntity> found = scan(outpost, snapshot.runId());
        RecoveryInput input = new RecoveryInput(
                snapshot.runId(),
                snapshot.wave(),
                snapshot.phase().hasCombat(),
                snapshot.mobsRemaining(),
                found,
                snapshot.phase() == RunPhase.BOSS,
                snapshot.bossId(),
                snapshot.bossStatus(),
                snapshot.bossEntityId(),
                snapshot.bossSpawnAttempts(),
                OutpostEngine.MAX_BOSS_SPAWNS);

        RecoveryPlan plan = RecoveryPlanner.plan(input);
        int removed = removeAll(plan.removeEntities());
        int respawned = topUpWave(outpost, config, snapshot, plan);
        String bossResult = applyBossAction(outpost, snapshot, plan);

        rebuildLiveMobs(outpost, snapshot);
        engine.persistState();

        String summary = "phase=" + snapshot.phase() + " wave=" + snapshot.wave()
                + " found=" + found.size() + " removed=" + removed + " respawned=" + respawned
                + " boss=" + bossResult + " moment=" + occasion;
        logger.info("[recovery] " + summary);
        engine.audit(AuditEvent.RECOVERY, "system", summary);
    }

    /**
     * The geometry the run was started with, as stored next to the run. Never the catalog entry: a
     * reload may have moved or removed the outpost since.
     */
    private Optional<OutpostDefinition> storedDefinition(PosterunkiRepository.LoadedState loaded, RunSnapshot snapshot) {
        String encoded = loaded.outpostDefinition();
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            OutpostDefinition definition = OutpostDefinitionCodec.decode(encoded);
            if (!definition.id().equals(snapshot.outpostId())) {
                logger.severe("[recovery] Zapisana geometria należy do posterunku '" + definition.id()
                        + "', a run do '" + snapshot.outpostId() + "' - traktuję geometrię jako nieznaną.");
                return Optional.empty();
            }
            return Optional.of(definition);
        } catch (IllegalArgumentException exception) {
            logger.severe("[recovery] Zapisana geometria runu jest nieczytelna: " + exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A run that holds a location but has no usable stored geometry - written by a build before the
     * geometry was persisted, or damaged.
     *
     * <p>The original region cannot be reconstructed, and the current catalog entry may already point
     * elsewhere. So the run is neither resumed as a fight nor declared clean: entities carrying this
     * run's tag are removed in every world (the tag, not the region, identifies them), and the run moves
     * to {@link RunPhase#FAILED} with a reason telling the administrator to inspect the location
     * before {@code /posterunki reset}. That reset restores the stored block states, which carry their
     * own coordinates, and says in the audit that fire, containers and items were not checked.
     */
    private void recoverWithoutGeometry(RunSnapshot snapshot, boolean idStillConfigured) {
        engine.state().bossEntityId().ifPresent(bossId -> {
            if (engine.context().bossAdapter() != null) {
                engine.context().bossAdapter().stop(bossId);
            }
        });
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (engine.context().tags().belongsTo(entity, snapshot.runId())) {
                    entity.remove();
                    removed++;
                }
            }
        }
        engine.liveMobs().clear();
        String summary = "brak-geometrii phase=" + snapshot.phase() + " removed=" + removed
                + " id-w-konfiguracji=" + (idStillConfigured ? "tak-niezweryfikowane" : "nie");
        engine.audit(AuditEvent.RECOVERY, "system", summary);
        logger.severe("[recovery] Run " + snapshot.runId() + " nie ma zapisanej geometrii posterunku '"
                + snapshot.outpostId() + "'. Nie wznawiam walki i nie uznaję lokalizacji za wyczyszczoną. "
                + summary);
        if (snapshot.phase() == RunPhase.FAILED) {
            engine.persistState();
            return;
        }
        engine.failRun("brak zapisanej geometrii posterunku '" + snapshot.outpostId()
                + "' (stan ze starszej wersji lub uszkodzony zapis) - lokalizacja nie została wyczyszczona;"
                + " sprawdź ją ręcznie i użyj /posterunki reset");
    }

    /**
     * Finishes a cleanup that the restart interrupted. Every entity of the run is removed - the
     * planner is told there is no fight - and nothing is spawned, re-attached or credited.
     */
    private void recoverInterruptedReset(OutpostDefinition outpost, PosterunkiConfig config, RunSnapshot snapshot) {
        engine.context().chunkTickets().acquire(outpost, config.protection().chunkTicketLimit());
        List<FoundEntity> found = scan(outpost, snapshot.runId());
        RecoveryPlan plan = RecoveryPlanner.plan(new RecoveryInput(snapshot.runId(), snapshot.wave(), false, 0,
                found, false, snapshot.bossId(), snapshot.bossStatus(), snapshot.bossEntityId(),
                snapshot.bossSpawnAttempts(), OutpostEngine.MAX_BOSS_SPAWNS));
        int removed = removeAll(plan.removeEntities());
        engine.liveMobs().clear();
        String summary = "phase=" + snapshot.phase() + " found=" + found.size() + " removed=" + removed
                + " reset=wznowiony";
        logger.info("[recovery] Przerwany reset posterunku '" + outpost.id() + "' zostanie dokończony. " + summary);
        engine.audit(AuditEvent.RECOVERY, "system", summary);
        engine.resumeInterruptedReset();
    }

    /**
     * The chunk ticket budget must cover the spawn points and the boss position; otherwise a
     * respawn would land in an unloaded chunk.
     */
    private void warnIfSpawnAreaUncovered(OutpostDefinition outpost, PosterunkiConfig config) {
        int needed = outpost.region().chunkCount();
        int budget = config.protection().chunkTicketLimit();
        if (budget >= needed) {
            return;
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            return;
        }
        List<String> uncovered = new ArrayList<>();
        outpost.spawnPoints().forEach((name, point) -> {
            if (!world.isChunkLoaded(point.blockX() >> 4, point.blockZ() >> 4)) {
                uncovered.add("spawn:" + name);
            }
        });
        PointDef boss = outpost.bossSpawn();
        if (!world.isChunkLoaded(boss.blockX() >> 4, boss.blockZ() >> 4)) {
            uncovered.add("boss-spawn");
        }
        if (!uncovered.isEmpty()) {
            logger.severe("[recovery] Limit chunków (" + budget + " z " + needed
                    + ") nie obejmuje: " + String.join(", ", uncovered)
                    + ". Zwiększ protection.chunk-ticket-limit albo zmniejsz region.");
        }
    }

    private List<FoundEntity> scan(OutpostDefinition outpost, String runId) {
        List<FoundEntity> found = new ArrayList<>();
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            logger.warning("[recovery] Świat '" + outpost.world() + "' nie jest załadowany.");
            return found;
        }
        Cuboid region = outpost.region();
        for (int cx = region.chunkMinX(); cx <= region.chunkMaxX(); cx++) {
            for (int cz = region.chunkMinZ(); cz <= region.chunkMaxZ(); cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    // Never force-load a chunk outside the ticket budget of the active outpost.
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (Entity entity : chunk.getEntities()) {
                    Optional<String> entityRun = engine.context().tags().runIdOf(entity);
                    if (entityRun.isEmpty() || engine.context().tags().isDisplay(entity)) {
                        continue;
                    }
                    found.add(new FoundEntity(entity.getUniqueId(), entityRun.get(),
                            engine.context().tags().waveOf(entity), engine.context().tags().isBoss(entity)));
                }
            }
        }
        // Entities of this run that drifted outside the scanned chunks are still ours.
        for (Entity entity : world.getEntities()) {
            if (!engine.context().tags().belongsTo(entity, runId)
                    || engine.context().tags().isDisplay(entity)) {
                continue;
            }
            UUID id = entity.getUniqueId();
            if (found.stream().noneMatch(candidate -> candidate.entityId().equals(id))) {
                found.add(new FoundEntity(id, runId, engine.context().tags().waveOf(entity),
                        engine.context().tags().isBoss(entity)));
            }
        }
        return found;
    }

    private int removeAll(List<UUID> ids) {
        int removed = 0;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private int topUpWave(OutpostDefinition outpost, PosterunkiConfig config, RunSnapshot snapshot,
                          RecoveryPlan plan) {
        if (plan.spawnMissingMobs() <= 0) {
            return 0;
        }
        WaveDefinition wave = config.wave(snapshot.wave());
        if (wave == null) {
            logger.warning("[recovery] Fala " + snapshot.wave() + " nie istnieje w konfiguracji.");
            return 0;
        }
        WaveSpawner.SpawnResult result = engine.context().waveSpawner()
                .spawnMissing(outpost, wave, snapshot.runId(), plan.spawnMissingMobs());
        result.problems().forEach(problem -> logger.warning("[recovery] " + problem));
        if (result.count() == 0 && plan.spawnMissingMobs() > 0) {
            engine.failRun("odtworzenie fali " + snapshot.wave() + " nie powiodło się: "
                    + String.join("; ", result.problems()));
        }
        return result.count();
    }

    private String applyBossAction(OutpostDefinition outpost, RunSnapshot snapshot, RecoveryPlan plan) {
        return switch (plan.bossAction()) {
            case NONE -> {
                if (snapshot.phase() == RunPhase.BOSS && snapshot.bossStatus().wantsEntity()) {
                    engine.failRun("boss '" + snapshot.bossId()
                            + "' nie istnieje, a limit prób przywołania został wyczerpany");
                    yield "budget-exhausted";
                }
                yield "none";
            }
            case REATTACH -> {
                // Does not consume a spawn attempt: nothing new was created.
                engine.reattachBoss(plan.reattachBossId());
                yield "reattached";
            }
            case SPAWN -> respawnBoss(outpost, snapshot);
        };
    }

    private String respawnBoss(OutpostDefinition outpost, RunSnapshot snapshot) {
        if (engine.context().bossAdapter() == null || !engine.context().bossAdapter().health().ready()) {
            engine.failRun("adapter bossa niedostępny przy odtwarzaniu runu");
            return "adapter-unavailable";
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            engine.failRun("świat '" + outpost.world() + "' nie jest załadowany przy odtwarzaniu runu");
            return "world-missing";
        }
        PointDef point = outpost.bossSpawn();
        Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        BossSpawnResult result = engine.context().bossAdapter().spawn(snapshot.bossId(), location);
        if (!result.success()) {
            engine.state().bossSpawnFailed();
            engine.failRun("nie udało się odtworzyć bossa '" + snapshot.bossId() + "': " + result.message());
            return "failed";
        }
        Entity entity = Bukkit.getEntity(result.entityId());
        if (entity != null) {
            engine.context().tags().tagBoss(entity, snapshot.runId(), outpost.id());
        }
        engine.state().bossSpawned(result.entityId());
        return "respawned";
    }

    private void rebuildLiveMobs(OutpostDefinition outpost, RunSnapshot snapshot) {
        engine.liveMobs().clear();
        World world = Bukkit.getWorld(outpost.world());
        if (world == null || !snapshot.phase().hasCombat()) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            if (!engine.context().tags().belongsTo(entity, snapshot.runId())
                    || engine.context().tags().isBoss(entity)
                    || engine.context().tags().isDisplay(entity)) {
                continue;
            }
            int wave = engine.context().tags().waveOf(entity);
            engine.liveMobs().put(entity.getUniqueId(),
                    new TrackedEntity(entity.getUniqueId(), TrackedEntity.Kind.MOB, null, wave));
        }
        engine.state().setMobsRemaining(engine.liveMobs().size());
    }
}
