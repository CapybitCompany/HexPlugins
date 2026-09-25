package hexposterunki.persistence;

import hex.core.api.db.Db;
import hexposterunki.domain.BossStatus;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * All SQL of HexPosterunki. Every method runs on a HexCore database thread via
 * {@code HexApi.db().async(...)}; nothing here touches Bukkit.
 *
 * <p>Two rules shape this class:
 * <ul>
 *   <li><b>One transaction per consistent state.</b> Global pointer, run, participants, tracked
 *       entities and - after a victory - the frozen standings and reward claims are written
 *       together in {@link #saveConsistent}. Inside the callback every
 *       statement uses the {@code Db} handed in by {@link Db#tx(java.util.function.Function)};
 *       using the outer client would silently leave the transaction.</li>
 *   <li><b>Revision ordering.</b> The transaction locks the state row, compares the stored
 *       revision and skips the whole write when the snapshot is stale, so an older write can never
 *       resurrect data a newer one removed.</li>
 * </ul>
 *
 * <p>The statements avoid vendor-specific constructs (no {@code INSERT IGNORE}, no
 * {@code ON DUPLICATE KEY UPDATE}) so the exact production SQL can be exercised in tests.
 */
public final class PosterunkiRepository implements SnapshotStore {

    private static final int GLOBAL_STATE_ID = 1;

    private final Db db;

    public PosterunkiRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    // ---------------------------------------------------------------- schema

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_state") + " ("
                + "id INT NOT NULL PRIMARY KEY,"
                + "run_id VARCHAR(36),"
                + "outpost_id VARCHAR(64),"
                + "last_outpost_id VARCHAR(64),"
                + "phase VARCHAR(16) NOT NULL,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "cooldown_until BIGINT NOT NULL DEFAULT 0,"
                + "updated_at BIGINT NOT NULL DEFAULT 0)");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_runs") + " ("
                + "run_id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "outpost_id VARCHAR(64) NOT NULL,"
                + "phase VARCHAR(16) NOT NULL,"
                + "wave INT NOT NULL DEFAULT 1,"
                + "controlling_town VARCHAR(36),"
                + "controlling_town_name VARCHAR(64),"
                + "boss_id VARCHAR(64),"
                + "boss_rolled INT NOT NULL DEFAULT 0,"
                + "boss_status VARCHAR(16) NOT NULL DEFAULT 'NONE',"
                + "boss_entity_uuid VARCHAR(36),"
                + "boss_spawn_attempts INT NOT NULL DEFAULT 0,"
                + "mobs_remaining INT NOT NULL DEFAULT 0,"
                + "started_at BIGINT NOT NULL DEFAULT 0,"
                + "window_expires_at BIGINT NOT NULL DEFAULT 0,"
                + "cooldown_until BIGINT NOT NULL DEFAULT 0,"
                + "next_phase_at BIGINT NOT NULL DEFAULT 0,"
                + "loot_until BIGINT NOT NULL DEFAULT 0,"
                + "failure_reason VARCHAR(255),"
                + "reset_cooldown BIGINT NOT NULL DEFAULT 0,"
                + "outpost_definition TEXT,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "updated_at BIGINT NOT NULL DEFAULT 0)");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_participants") + " ("
                + "run_id VARCHAR(36) NOT NULL,"
                + "player_uuid VARCHAR(36) NOT NULL,"
                + "town_id VARCHAR(36),"
                + "kills INT NOT NULL DEFAULT 0,"
                + "achieved_at BIGINT NOT NULL DEFAULT 0,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (run_id, player_uuid))");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_entities") + " ("
                + "run_id VARCHAR(36) NOT NULL,"
                + "entity_uuid VARCHAR(36) NOT NULL,"
                + "kind VARCHAR(8) NOT NULL,"
                + "mob_id VARCHAR(64),"
                + "wave INT NOT NULL DEFAULT 0,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (run_id, entity_uuid))");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_reward_claims") + " ("
                + "idempotency_key VARCHAR(190) NOT NULL PRIMARY KEY,"
                + "run_id VARCHAR(36) NOT NULL,"
                + "outpost_id VARCHAR(64),"
                + "player_uuid VARCHAR(36) NOT NULL,"
                + "place INT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "attempts INT NOT NULL DEFAULT 0,"
                + "progress INT NOT NULL DEFAULT 0,"
                + "payload TEXT,"
                + "last_error VARCHAR(255),"
                + "created_at BIGINT NOT NULL,"
                + "updated_at BIGINT NOT NULL)");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_audit") + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "run_id VARCHAR(36),"
                + "outpost_id VARCHAR(64),"
                + "event VARCHAR(32) NOT NULL,"
                + "actor VARCHAR(64),"
                + "data VARCHAR(512),"
                + "created_at BIGINT NOT NULL)");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_stats") + " ("
                + "run_id VARCHAR(36) NOT NULL,"
                + "player_uuid VARCHAR(36) NOT NULL,"
                + "town_id VARCHAR(36),"
                + "outpost_id VARCHAR(64),"
                + "kills INT NOT NULL DEFAULT 0,"
                + "place INT NOT NULL DEFAULT 0,"
                + "completed_at BIGINT NOT NULL,"
                + "PRIMARY KEY (run_id, player_uuid))");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_loot") + " ("
                + "run_id VARCHAR(36) NOT NULL,"
                + "container_id VARCHAR(64) NOT NULL,"
                + "filled_at BIGINT NOT NULL,"
                + "PRIMARY KEY (run_id, container_id))");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("posterunki_blocks") + " ("
                + "run_id VARCHAR(36) NOT NULL,"
                + "world VARCHAR(64) NOT NULL,"
                + "x INT NOT NULL,"
                + "y INT NOT NULL,"
                + "z INT NOT NULL,"
                + "block_data VARCHAR(512) NOT NULL,"
                + "PRIMARY KEY (run_id, world, x, y, z))");

        migrate();
    }

    /**
     * Adds columns introduced after the first release. Existing installations keep their data;
     * new columns get the default that reproduces the old behaviour.
     */
    private void migrate() {
        ensureColumn("posterunki_runs", "next_phase_at", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("posterunki_runs", "loot_until", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("posterunki_runs", "failure_reason", "VARCHAR(255)");
        ensureColumn("posterunki_runs", "reset_cooldown", "BIGINT NOT NULL DEFAULT 0");
        // Runs written before this column existed keep NULL: their geometry is unknown, and recovery
        // treats them as such instead of re-resolving the id against the current catalog.
        ensureColumn("posterunki_runs", "outpost_definition", "TEXT");
        ensureColumn("posterunki_participants", "revision", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn("posterunki_entities", "revision", "BIGINT NOT NULL DEFAULT 0");
    }

    /**
     * Adds a column to the table in the schema this connection works in.
     *
     * <p>{@code information_schema} lists every schema the account can see - on a shared MySQL server
     * other servers' HexPosterunki tables included. The lookup is therefore restricted to the current
     * schema: {@code SCHEMA()} is the current database in MySQL and MariaDB (a synonym of
     * {@code DATABASE()}) and the current schema in H2, which the tests use.
     */
    private void ensureColumn(String table, String column, String definition) {
        String full = db.t(table);
        boolean present = db.queryOne(
                "SELECT COUNT(*) AS c FROM information_schema.columns "
                        + "WHERE UPPER(table_schema) = UPPER(SCHEMA())"
                        + " AND UPPER(table_name) = ? AND UPPER(column_name) = ?",
                rs -> rs.getInt("c") > 0,
                full.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT)).orElse(false);
        if (!present) {
            db.update("ALTER TABLE " + full + " ADD COLUMN " + column + " " + definition);
        }
    }

    // ---------------------------------------------------------------- consistent snapshot

    @Override
    public WriteResult saveConsistent(RunPersistenceSnapshot snapshot, long nowMillis) {
        return db.tx(tx -> {
            // Lock the pointer row first: it serialises concurrent writers and gives us the
            // authoritative stored revision inside this transaction.
            long stored = tx.queryOne("SELECT revision FROM " + tx.t("posterunki_state")
                            + " WHERE id = ? FOR UPDATE",
                    rs -> rs.getLong("revision"), GLOBAL_STATE_ID).orElse(-1L);
            RunSnapshot run = snapshot.run();
            if (run.revision() <= stored) {
                return WriteResult.STALE;
            }

            writeState(tx, snapshot, nowMillis);
            if (run.hasRun()) {
                writeRun(tx, run, snapshot.outpostDefinition(), nowMillis);
                replaceParticipants(tx, run.runId(), run.revision(), snapshot.participants());
                replaceEntities(tx, run.runId(), run.revision(), snapshot.entities());
            }
            if (snapshot.completion() != null) {
                // Same transaction as the COMPLETED phase: no completion without its claims,
                // no claims without a completion. Both statements are idempotent.
                writeCompletion(tx, snapshot.completion(), nowMillis);
            }
            return WriteResult.WRITTEN;
        });
    }

    private void writeState(Db tx, RunPersistenceSnapshot snapshot, long nowMillis) {
        RunSnapshot run = snapshot.run();
        int updated = tx.update("UPDATE " + tx.t("posterunki_state") + " SET run_id = ?, outpost_id = ?,"
                        + " last_outpost_id = ?, phase = ?, revision = ?, cooldown_until = ?, updated_at = ?"
                        + " WHERE id = ?",
                run.runId(), run.outpostId(), snapshot.lastOutpostId(), run.phase().name(),
                run.revision(), run.cooldownUntilMillis(), nowMillis, GLOBAL_STATE_ID);
        if (updated == 0) {
            tx.update("INSERT INTO " + tx.t("posterunki_state")
                            + " (id, run_id, outpost_id, last_outpost_id, phase, revision, cooldown_until, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    GLOBAL_STATE_ID, run.runId(), run.outpostId(), snapshot.lastOutpostId(),
                    run.phase().name(), run.revision(), run.cooldownUntilMillis(), nowMillis);
        }
    }

    private void writeRun(Db tx, RunSnapshot run, String outpostDefinition, long nowMillis) {
        int updated = tx.update("UPDATE " + tx.t("posterunki_runs") + " SET outpost_id = ?, phase = ?, wave = ?,"
                        + " controlling_town = ?, controlling_town_name = ?, boss_id = ?, boss_rolled = ?,"
                        + " boss_status = ?, boss_entity_uuid = ?, boss_spawn_attempts = ?, mobs_remaining = ?,"
                        + " started_at = ?, window_expires_at = ?, cooldown_until = ?, next_phase_at = ?,"
                        + " loot_until = ?, failure_reason = ?, reset_cooldown = ?,"
                        + " outpost_definition = COALESCE(?, outpost_definition), revision = ?, updated_at = ?"
                        + " WHERE run_id = ? AND revision <= ?",
                run.outpostId(), run.phase().name(), run.wave(), asString(run.controllingTown()),
                run.controllingTownName(), run.bossId(), run.bossRolled() ? 1 : 0,
                run.bossStatus().name(), asString(run.bossEntityId()), run.bossSpawnAttempts(),
                run.mobsRemaining(), run.startedAtMillis(), run.windowExpiresAtMillis(),
                run.cooldownUntilMillis(), run.nextPhaseAtMillis(), run.lootUntilMillis(),
                truncate(run.failureReason(), 255), run.resetCooldownMillis(), outpostDefinition,
                run.revision(), nowMillis, run.runId(), run.revision());
        if (updated == 0) {
            boolean exists = tx.queryOne("SELECT 1 AS present FROM " + tx.t("posterunki_runs") + " WHERE run_id = ?",
                    rs -> true, run.runId()).orElse(false);
            if (exists) {
                // A newer revision of this run is already stored; leave it alone.
                return;
            }
            tx.update("INSERT INTO " + tx.t("posterunki_runs")
                            + " (run_id, outpost_id, phase, wave, controlling_town, controlling_town_name,"
                            + " boss_id, boss_rolled, boss_status, boss_entity_uuid, boss_spawn_attempts,"
                            + " mobs_remaining, started_at, window_expires_at, cooldown_until, next_phase_at,"
                            + " loot_until, failure_reason, reset_cooldown, outpost_definition, revision, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.runId(), run.outpostId(), run.phase().name(), run.wave(),
                    asString(run.controllingTown()), run.controllingTownName(), run.bossId(),
                    run.bossRolled() ? 1 : 0, run.bossStatus().name(), asString(run.bossEntityId()),
                    run.bossSpawnAttempts(), run.mobsRemaining(), run.startedAtMillis(),
                    run.windowExpiresAtMillis(), run.cooldownUntilMillis(), run.nextPhaseAtMillis(),
                    run.lootUntilMillis(), truncate(run.failureReason(), 255), run.resetCooldownMillis(),
                    outpostDefinition, run.revision(), nowMillis);
        }
    }

    private void replaceParticipants(Db tx, String runId, long revision, List<KillEntry> entries) {
        // Only rows written by an older or equal revision are replaced. A kill reset therefore
        // removes exactly the rows it knows about and a late older write cannot bring them back.
        tx.update("DELETE FROM " + tx.t("posterunki_participants") + " WHERE run_id = ? AND revision <= ?",
                runId, revision);
        if (entries.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (KillEntry entry : entries) {
            batch.add(new Object[]{runId, entry.playerId().toString(), asString(entry.townId()),
                    entry.kills(), entry.achievedAtMillis(), revision});
        }
        tx.batch("INSERT INTO " + tx.t("posterunki_participants")
                + " (run_id, player_uuid, town_id, kills, achieved_at, revision) VALUES (?,?,?,?,?,?)", batch);
    }

    private void writeCompletion(Db tx, CompletionRecord completion, long nowMillis) {
        insertClaimsIfAbsent(tx, completion.claims(), nowMillis);
        upsertStats(tx, completion.runId(), completion.outpostId(), completion.stats(),
                completion.completedAtMillis());
    }

    private void replaceEntities(Db tx, String runId, long revision, List<TrackedEntity> entities) {
        tx.update("DELETE FROM " + tx.t("posterunki_entities") + " WHERE run_id = ? AND revision <= ?",
                runId, revision);
        if (entities.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(entities.size());
        for (TrackedEntity entity : entities) {
            batch.add(new Object[]{runId, entity.entityId().toString(), entity.kind().name(),
                    entity.mobId(), entity.wave(), revision});
        }
        tx.batch("INSERT INTO " + tx.t("posterunki_entities")
                + " (run_id, entity_uuid, kind, mob_id, wave, revision) VALUES (?,?,?,?,?,?)", batch);
    }

    // ---------------------------------------------------------------- reads

    /**
     * Loads the last consistent state. When the pointer and the run row disagree - which a
     * partially applied write of an older build could have left behind - the run is reported as
     * inconsistent so recovery starts clean instead of resuming garbage.
     */
    public LoadedState loadAll() {
        return db.tx(tx -> {
            Optional<GlobalState> global = tx.queryOne("SELECT run_id, outpost_id, last_outpost_id, phase,"
                            + " revision, cooldown_until FROM " + tx.t("posterunki_state") + " WHERE id = ?",
                    rs -> new GlobalState(rs.getString("run_id"), rs.getString("outpost_id"),
                            rs.getString("last_outpost_id"),
                            RunPhase.parse(rs.getString("phase"), RunPhase.COOLDOWN),
                            rs.getLong("revision"), rs.getLong("cooldown_until")),
                    GLOBAL_STATE_ID);
            if (global.isEmpty()) {
                return LoadedState.empty();
            }
            GlobalState state = global.get();
            if (state.runId() == null) {
                return new LoadedState(state, null, List.of(), List.of(), List.of(), true);
            }
            StoredRun stored = tx.queryOne("SELECT * FROM " + tx.t("posterunki_runs") + " WHERE run_id = ?",
                    rs -> new StoredRun(mapRun(rs), rs.getString("outpost_definition")), state.runId()).orElse(null);
            RunSnapshot run = stored == null ? null : stored.run();
            if (run == null || run.revision() != state.revision()) {
                return new LoadedState(state, run, List.of(), List.of(), List.of(), false);
            }
            List<KillEntry> participants = tx.query("SELECT player_uuid, town_id, kills, achieved_at FROM "
                            + tx.t("posterunki_participants") + " WHERE run_id = ?",
                    rs -> new KillEntry(UUID.fromString(rs.getString("player_uuid")),
                            optionalUuid(rs.getString("town_id")), rs.getInt("kills"),
                            rs.getLong("achieved_at")),
                    state.runId());
            List<TrackedEntity> entities = tx.query("SELECT entity_uuid, kind, mob_id, wave FROM "
                            + tx.t("posterunki_entities") + " WHERE run_id = ?",
                    rs -> new TrackedEntity(UUID.fromString(rs.getString("entity_uuid")),
                            TrackedEntity.Kind.valueOf(rs.getString("kind")),
                            rs.getString("mob_id"), rs.getInt("wave")),
                    state.runId());
            List<String> loot = tx.query("SELECT container_id FROM " + tx.t("posterunki_loot")
                    + " WHERE run_id = ?", rs -> rs.getString("container_id"), state.runId());
            return new LoadedState(state, run, participants, entities, loot, true, stored.outpostDefinition());
        });
    }

    // ---------------------------------------------------------------- loot / blocks

    /**
     * Claims one container fill for this run.
     *
     * @return true when this caller won the claim and must actually fill the container
     */
    public boolean markContainerFilled(String runId, String containerId, long nowMillis) {
        return db.tx(tx -> {
            boolean present = tx.queryOne("SELECT 1 AS present FROM " + tx.t("posterunki_loot")
                            + " WHERE run_id = ? AND container_id = ? FOR UPDATE",
                    rs -> true, runId, containerId).orElse(false);
            if (present) {
                return false;
            }
            tx.update("INSERT INTO " + tx.t("posterunki_loot") + " (run_id, container_id, filled_at)"
                    + " VALUES (?,?,?)", runId, containerId, nowMillis);
            return true;
        });
    }

    public void clearLoot(String runId) {
        db.update("DELETE FROM " + db.t("posterunki_loot") + " WHERE run_id = ?", runId);
    }

    public List<BlockSnapshotEntry> loadBlockSnapshot(String runId) {
        if (runId == null) {
            return List.of();
        }
        return db.query("SELECT world, x, y, z, block_data FROM " + db.t("posterunki_blocks") + " WHERE run_id = ?",
                rs -> new BlockSnapshotEntry(rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
                        rs.getInt("z"), rs.getString("block_data")),
                runId);
    }

    public void saveBlockSnapshot(String runId, List<BlockSnapshotEntry> entries) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("posterunki_blocks") + " WHERE run_id = ?", runId);
            if (entries.isEmpty()) {
                return null;
            }
            List<Object[]> batch = new ArrayList<>(entries.size());
            for (BlockSnapshotEntry entry : entries) {
                batch.add(new Object[]{runId, entry.world(), entry.x(), entry.y(), entry.z(), entry.blockData()});
            }
            tx.batch("INSERT INTO " + tx.t("posterunki_blocks")
                    + " (run_id, world, x, y, z, block_data) VALUES (?,?,?,?,?,?)", batch);
            return null;
        });
    }

    public void clearBlockSnapshot(String runId) {
        db.update("DELETE FROM " + db.t("posterunki_blocks") + " WHERE run_id = ?", runId);
    }

    public void clearRunEntities(String runId) {
        db.update("DELETE FROM " + db.t("posterunki_entities") + " WHERE run_id = ?", runId);
    }

    // ---------------------------------------------------------------- reward claims

    /**
     * Stores the frozen entitlements of a finished run. Existing claims are never overwritten, so
     * a replayed completion or a repeated recovery cannot change what was already earned.
     *
     * @return the number of claims newly inserted
     */
    public int insertClaims(List<RewardClaim> claims, long nowMillis) {
        if (claims.isEmpty()) {
            return 0;
        }
        return db.tx(tx -> insertClaimsIfAbsent(tx, claims, nowMillis));
    }

    private int insertClaimsIfAbsent(Db tx, List<RewardClaim> claims, long nowMillis) {
        int inserted = 0;
        for (RewardClaim claim : claims) {
            boolean present = tx.queryOne("SELECT 1 AS present FROM " + tx.t("posterunki_reward_claims")
                            + " WHERE idempotency_key = ? FOR UPDATE",
                    rs -> true, claim.key()).orElse(false);
            if (present) {
                continue;
            }
            tx.update("INSERT INTO " + tx.t("posterunki_reward_claims")
                            + " (idempotency_key, run_id, outpost_id, player_uuid, place, status,"
                            + " attempts, progress, payload, last_error, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    claim.key(), claim.runId(), claim.outpostId(), claim.playerId().toString(),
                    claim.place(), claim.status().name(), claim.attempts(), claim.progress(),
                    claim.payload(), truncate(claim.lastError(), 255), nowMillis, nowMillis);
            inserted++;
        }
        return inserted;
    }

    /**
     * Moves a claim from CLAIMED into DELIVERING and returns it, or empty when another path
     * already took it. The row lock makes the hand-over single-winner.
     */
    public Optional<RewardClaim> beginDelivery(String key, long nowMillis) {
        return db.tx(tx -> {
            Optional<RewardClaim> current = tx.queryOne("SELECT * FROM " + tx.t("posterunki_reward_claims")
                    + " WHERE idempotency_key = ? FOR UPDATE", PosterunkiRepository::mapClaim, key);
            if (current.isEmpty() || current.get().status() != RewardClaim.Status.CLAIMED) {
                return Optional.<RewardClaim>empty();
            }
            RewardClaim claim = current.get();
            tx.update("UPDATE " + tx.t("posterunki_reward_claims")
                            + " SET status = ?, attempts = ?, updated_at = ? WHERE idempotency_key = ?",
                    RewardClaim.Status.DELIVERING.name(), claim.attempts() + 1, nowMillis, key);
            return Optional.of(new RewardClaim(claim.runId(), claim.outpostId(), claim.playerId(),
                    claim.place(), RewardClaim.Status.DELIVERING, claim.attempts() + 1,
                    claim.progress(), claim.payload(), claim.lastError()));
        });
    }

    public void finishDelivery(String key, RewardClaim.Status status, int progress, String error, long nowMillis) {
        db.update("UPDATE " + db.t("posterunki_reward_claims")
                        + " SET status = ?, progress = ?, last_error = ?, updated_at = ? WHERE idempotency_key = ?",
                status.name(), progress, truncate(error, 255), nowMillis, key);
    }

    public List<RewardClaim> loadClaims(RewardClaim.Status status) {
        return db.query("SELECT * FROM " + db.t("posterunki_reward_claims") + " WHERE status = ?",
                PosterunkiRepository::mapClaim, status.name());
    }

    public List<RewardClaim> loadClaimsForPlayer(UUID playerId, RewardClaim.Status status) {
        return db.query("SELECT * FROM " + db.t("posterunki_reward_claims")
                        + " WHERE player_uuid = ? AND status = ?",
                PosterunkiRepository::mapClaim, playerId.toString(), status.name());
    }

    public Optional<RewardClaim> loadClaim(String key) {
        return db.queryOne("SELECT * FROM " + db.t("posterunki_reward_claims") + " WHERE idempotency_key = ?",
                PosterunkiRepository::mapClaim, key);
    }

    /**
     * Startup safety net: a claim stuck in DELIVERING survived a crash, so nobody can tell whether
     * the items landed or the console command ran. Such claims are parked for an administrator
     * instead of being paid again or silently dropped.
     */
    public int quarantineInterruptedDeliveries(long nowMillis) {
        return db.update("UPDATE " + db.t("posterunki_reward_claims")
                        + " SET status = ?, last_error = ?, updated_at = ? WHERE status = ?",
                RewardClaim.Status.NEEDS_REVIEW.name(),
                "Serwer zatrzymany w trakcie wydawania nagrody - wymagana decyzja administratora",
                nowMillis, RewardClaim.Status.DELIVERING.name());
    }

    // ---------------------------------------------------------------- stats / audit

    public List<StatRow> loadCompletionStats(String runId) {
        return db.query("SELECT player_uuid, town_id, kills, place FROM " + db.t("posterunki_stats")
                        + " WHERE run_id = ?",
                rs -> new StatRow(UUID.fromString(rs.getString("player_uuid")),
                        optionalUuid(rs.getString("town_id")), rs.getInt("kills"), rs.getInt("place")),
                runId);
    }

    private void upsertStats(Db tx, String runId, String outpostId, List<StatRow> rows, long completedAtMillis) {
        for (StatRow row : rows) {
            int updated = tx.update("UPDATE " + tx.t("posterunki_stats")
                            + " SET town_id = ?, outpost_id = ?, kills = ?, place = ?, completed_at = ?"
                            + " WHERE run_id = ? AND player_uuid = ?",
                    asString(row.townId()), outpostId, row.kills(), row.place(), completedAtMillis,
                    runId, row.playerId().toString());
            if (updated == 0) {
                tx.update("INSERT INTO " + tx.t("posterunki_stats")
                                + " (run_id, player_uuid, town_id, outpost_id, kills, place, completed_at)"
                                + " VALUES (?,?,?,?,?,?,?)",
                        runId, row.playerId().toString(), asString(row.townId()), outpostId,
                        row.kills(), row.place(), completedAtMillis);
            }
        }
    }

    public void audit(String runId, String outpostId, String event, String actor, String data, long nowMillis) {
        db.update("INSERT INTO " + db.t("posterunki_audit")
                        + " (run_id, outpost_id, event, actor, data, created_at) VALUES (?,?,?,?,?,?)",
                runId, outpostId, event, truncate(actor, 64), truncate(data, 512), nowMillis);
    }

    public List<String> recentAudit(int limit) {
        return db.query("SELECT event, actor, data, created_at FROM " + db.t("posterunki_audit")
                        + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 100)),
                rs -> rs.getLong("created_at") + " " + rs.getString("event")
                        + " " + String.valueOf(rs.getString("actor"))
                        + " " + String.valueOf(rs.getString("data")));
    }

    // ---------------------------------------------------------------- helpers

    private static RunSnapshot mapRun(ResultSet rs) throws SQLException {
        return new RunSnapshot(
                rs.getString("run_id"),
                rs.getString("outpost_id"),
                RunPhase.parse(rs.getString("phase"), RunPhase.COOLDOWN),
                rs.getInt("wave"),
                optionalUuid(rs.getString("controlling_town")),
                rs.getString("controlling_town_name"),
                rs.getString("boss_id"),
                rs.getInt("boss_rolled") == 1,
                BossStatus.parse(rs.getString("boss_status"), BossStatus.NONE),
                optionalUuid(rs.getString("boss_entity_uuid")),
                rs.getInt("boss_spawn_attempts"),
                rs.getInt("mobs_remaining"),
                rs.getLong("started_at"),
                rs.getLong("window_expires_at"),
                rs.getLong("cooldown_until"),
                rs.getLong("next_phase_at"),
                rs.getLong("loot_until"),
                rs.getString("failure_reason"),
                rs.getLong("revision"),
                rs.getLong("reset_cooldown"));
    }

    private static RewardClaim mapClaim(ResultSet rs) throws SQLException {
        return new RewardClaim(
                rs.getString("run_id"),
                rs.getString("outpost_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getInt("place"),
                RewardClaim.Status.parse(rs.getString("status"), RewardClaim.Status.NEEDS_REVIEW),
                rs.getInt("attempts"),
                rs.getInt("progress"),
                rs.getString("payload"),
                rs.getString("last_error"));
    }

    private static UUID optionalUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String asString(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Pointer row describing what the server was doing when it stopped. */
    public record GlobalState(String runId, String outpostId, String lastOutpostId,
                              RunPhase phase, long revision, long cooldownUntilMillis) {
    }

    /** Aggregated per-player statistics written once on completion. */
    public record StatRow(UUID playerId, UUID townId, int kills, int place) {
    }

    /** Run row together with the location definition stored for it (null for older rows). */
    private record StoredRun(RunSnapshot run, String outpostDefinition) {
    }

    /**
     * @param consistent        false when the pointer row and the run row disagree; recovery then
     *                          refuses to resume the run instead of resuming half of it
     * @param outpostDefinition encoded definition the run holds its location with; null when the
     *                          row predates the column or no definition was ever stored
     */
    public record LoadedState(GlobalState global, RunSnapshot run, List<KillEntry> participants,
                              List<TrackedEntity> entities, List<String> filledContainers,
                              boolean consistent, String outpostDefinition) {

        public LoadedState(GlobalState global, RunSnapshot run, List<KillEntry> participants,
                           List<TrackedEntity> entities, List<String> filledContainers, boolean consistent) {
            this(global, run, participants, entities, filledContainers, consistent, null);
        }

        public static LoadedState empty() {
            return new LoadedState(null, null, List.of(), List.of(), List.of(), true);
        }

        /**
         * Highest revision the database holds for the global state, including a discarded run row.
         * Recovery adopts it so that every new state is written above it.
         */
        public long persistedRevision() {
            long revision = global == null ? 0L : global.revision();
            return run == null ? revision : Math.max(revision, run.revision());
        }

        public boolean hasRun() {
            return global != null && run != null && consistent;
        }
    }
}
