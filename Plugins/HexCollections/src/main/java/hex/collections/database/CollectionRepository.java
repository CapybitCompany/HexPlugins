package hex.collections.database;

import hex.core.api.db.Db;
import hex.collections.api.CollectionProgress;
import hex.collections.api.TopCollectionEntry;
import hex.collections.model.CollectionScalingState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CollectionRepository {
    private final Db db;

    public CollectionRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("collection_progress") + " (" +
                "town_id CHAR(36) NOT NULL," +
                "collection_id VARCHAR(128) NOT NULL," +
                "amount BIGINT NOT NULL DEFAULT 0," +
                "level INT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, collection_id)," +
                "KEY idx_town (town_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("collection_events") + " (" +
                "id BIGINT NOT NULL AUTO_INCREMENT," +
                "town_id CHAR(36) NOT NULL," +
                "player_uuid CHAR(36) NOT NULL," +
                "collection_id VARCHAR(128) NOT NULL," +
                "amount BIGINT NOT NULL," +
                "source VARCHAR(64) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "metadata TEXT NULL," +
                "PRIMARY KEY (id)," +
                "KEY idx_town_collection (town_id, collection_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("collection_scaling") + " (" +
                "town_id CHAR(36) NOT NULL," +
                "collection_id VARCHAR(128) NOT NULL," +
                "target_level INT NOT NULL," +
                "effective_member_count INT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, collection_id)," +
                "KEY idx_scaling_town (town_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public Map<String, CollectionProgress> loadTown(UUID townId) {
        Map<String, CollectionProgress> result = new HashMap<>();
        db.query("SELECT collection_id, amount, level FROM " + db.t("collection_progress") + " WHERE town_id=?",
                rs -> {
                    String id = rs.getString("collection_id");
                    result.put(id, new CollectionProgress(id, rs.getLong("amount"), rs.getInt("level")));
                    return null;
                }, townId.toString());
        return result;
    }

    public Map<String, CollectionScalingState> loadScalingTown(UUID townId) {
        Map<String, CollectionScalingState> result = new HashMap<>();
        db.query("SELECT collection_id, target_level, effective_member_count FROM " + db.t("collection_scaling") + " WHERE town_id=?",
                rs -> {
                    String id = rs.getString("collection_id");
                    result.put(id, new CollectionScalingState(id, rs.getInt("target_level"), rs.getInt("effective_member_count")));
                    return null;
                }, townId.toString());
        return result;
    }

    public void upsertTown(UUID townId, Map<String, CollectionProgress> progress) {
        if (townId == null || progress == null || progress.isEmpty()) return;
        String id = townId.toString();
        db.tx(tx -> {
            for (CollectionProgress value : progress.values()) {
                // Atomic lifecycle guard: once HexTowns flips the town to DESTROYING this INSERT
                // cannot resurrect collection rows, even if the async task was queued earlier.
                tx.update("INSERT INTO " + tx.t("collection_progress") +
                                " (town_id, collection_id, amount, level, updated_at) " +
                                "SELECT ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("towns") + " WHERE uuid=UNHEX(REPLACE(?, '-', '')) AND status='ACTIVE') " +
                                "ON DUPLICATE KEY UPDATE " +
                                "amount=GREATEST(amount, VALUES(amount)), " +
                                "level=GREATEST(level, VALUES(level)), " +
                                "updated_at=GREATEST(updated_at, VALUES(updated_at))",
                        id, value.collectionId(), value.amount(), value.level(), System.currentTimeMillis(), id);
            }
            return null;
        });
    }

    public void upsertScaling(UUID townId, CollectionScalingState state) {
        if (townId == null || state == null) return;
        String id = townId.toString();
        db.update("INSERT INTO " + db.t("collection_scaling") +
                        " (town_id, collection_id, target_level, effective_member_count, updated_at) " +
                        "SELECT ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE uuid=UNHEX(REPLACE(?, '-', '')) AND status='ACTIVE') " +
                        "ON DUPLICATE KEY UPDATE " +
                        "effective_member_count=CASE " +
                        "WHEN VALUES(target_level)>target_level THEN VALUES(effective_member_count) " +
                        "WHEN VALUES(target_level)=target_level THEN GREATEST(effective_member_count, VALUES(effective_member_count)) " +
                        "ELSE effective_member_count END, " +
                        "target_level=GREATEST(target_level, VALUES(target_level)), updated_at=VALUES(updated_at)",
                id, state.collectionId(), state.targetLevel(), state.effectiveMemberCount(), System.currentTimeMillis(), id);
    }

    public void raiseScalingMembers(UUID townId, int memberCount) {
        db.update("UPDATE " + db.t("collection_scaling") +
                        " SET effective_member_count=GREATEST(effective_member_count, ?), updated_at=? WHERE town_id=?",
                Math.max(1, memberCount), System.currentTimeMillis(), townId.toString());
    }

    public Optional<CollectionProgress> getProgress(UUID townId, String collectionId) {
        return db.queryOne("SELECT amount, level FROM " + db.t("collection_progress") +
                        " WHERE town_id=? AND collection_id=?",
                rs -> new CollectionProgress(collectionId, rs.getLong("amount"), rs.getInt("level")), townId.toString(), collectionId);
    }

    public List<TopCollectionEntry> top(String collectionId, int limit) {
        int capped = Math.max(1, Math.min(limit, 25));
        return db.query("SELECT town_id, collection_id, amount, level FROM " + db.t("collection_progress") +
                        " WHERE collection_id=? ORDER BY amount DESC, level DESC LIMIT ?",
                rs -> new TopCollectionEntry(UUID.fromString(rs.getString("town_id")), rs.getString("collection_id"), rs.getLong("amount"), rs.getInt("level")),
                collectionId, capped);
    }

    public void purgeTown(UUID townId) {
        if (townId == null) return;
        String id = townId.toString();
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("collection_progress") + " WHERE town_id=?", id);
            tx.update("DELETE FROM " + tx.t("collection_events") + " WHERE town_id=?", id);
            tx.update("DELETE FROM " + tx.t("collection_scaling") + " WHERE town_id=?", id);
            return null;
        });
    }

    public void purgeTownVerified(UUID townId) {
        purgeTown(townId);
        int remaining = countTownRows(townId);
        if (remaining != 0) throw new IllegalStateException("Collection purge verification failed for town=" + townId + " remaining=" + remaining);
    }

    public int countTownRows(UUID townId) {
        if (townId == null) return 0;
        String id = townId.toString();
        int progress = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("collection_progress") + " WHERE town_id=?", rs -> rs.getInt("c"), id).orElse(0);
        int events = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("collection_events") + " WHERE town_id=?", rs -> rs.getInt("c"), id).orElse(0);
        int scaling = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("collection_scaling") + " WHERE town_id=?", rs -> rs.getInt("c"), id).orElse(0);
        return progress + events + scaling;
    }
}
