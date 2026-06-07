package hex.collections.database;

import hex.core.api.db.Db;
import hex.collections.api.CollectionProgress;

import java.util.HashMap;
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

	public void upsertTown(UUID townId, Map<String, CollectionProgress> progress) {
		db.tx(tx -> {
			for (CollectionProgress value : progress.values()) {
				tx.update("INSERT INTO " + tx.t("collection_progress") +
								" (town_id, collection_id, amount, level, updated_at) VALUES (?, ?, ?, ?, ?) " +
								"ON DUPLICATE KEY UPDATE amount=VALUES(amount), level=VALUES(level), updated_at=VALUES(updated_at)",
						townId.toString(), value.collectionId(), value.amount(), value.level(), System.currentTimeMillis());
			}
			return null;
		});
	}

	public Optional<CollectionProgress> getProgress(UUID townId, String collectionId) {
		return db.queryOne("SELECT amount, level FROM " + db.t("collection_progress") +
						" WHERE town_id=? AND collection_id=?",
				rs -> new CollectionProgress(collectionId, rs.getLong("amount"), rs.getInt("level")), townId.toString(), collectionId);
	}

	public void purgeTown(UUID townId) {
		db.update("DELETE FROM " + db.t("collection_progress") + " WHERE town_id=?", townId.toString());
		db.update("DELETE FROM " + db.t("collection_events") + " WHERE town_id=?", townId.toString());
	}
}

