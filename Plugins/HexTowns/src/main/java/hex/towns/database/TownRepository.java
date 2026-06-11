package hex.towns.database;

import hex.core.api.db.Db;
import hex.towns.api.Page;
import hex.towns.model.ChunkPos;
import hex.towns.model.Town;
import hex.towns.model.TownRole;
import hex.towns.model.TownStatus;
import hex.towns.util.UuidBytes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TownRepository {
    private final Db db;

    public TownRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_worlds") + " (" +
                "id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                "name VARCHAR(64) NOT NULL," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uq_name (name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("towns") + " (" +
                "id BIGINT UNSIGNED NOT NULL," +
                "uuid BINARY(16) NOT NULL," +
                "owner_uuid BINARY(16) NOT NULL," +
                "name VARCHAR(64) NOT NULL," +
                "world_id SMALLINT UNSIGNED NOT NULL," +
                "heart_cx INT NOT NULL," +
                "heart_cz INT NOT NULL," +
                "growth_points INT NOT NULL DEFAULT 0," +
                "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                "created_at BIGINT NOT NULL," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uq_uuid (uuid)," +
                "UNIQUE KEY uq_owner (owner_uuid)," +
                "KEY idx_world_heart (world_id, heart_cx, heart_cz)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_chunks") + " (" +
                "world_id SMALLINT UNSIGNED NOT NULL," +
                "cx INT NOT NULL," +
                "cz INT NOT NULL," +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "bucket_x INT NOT NULL," +
                "bucket_z INT NOT NULL," +
                "PRIMARY KEY (world_id, cx, cz)," +
                "KEY idx_town (town_id)," +
                "KEY idx_bucket (world_id, bucket_x, bucket_z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_members") + " (" +
                "uuid BINARY(16) NOT NULL," +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "role TINYINT UNSIGNED NOT NULL," +
                "joined_at BIGINT NOT NULL," +
                "PRIMARY KEY (uuid)," +
                "KEY idx_town (town_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_meta") + " (" +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "ns VARCHAR(32) NOT NULL," +
                "k VARCHAR(96) NOT NULL," +
                "v VARCHAR(255) NOT NULL," +
                "PRIMARY KEY (town_id, ns, k)," +
                "KEY idx_ns (ns)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_coop_requests") + " (" +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "requester BINARY(16) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, requester)," +
                "KEY idx_requester (requester)," +
                "KEY idx_created (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_data_namespaces") + " (" +
                "ns VARCHAR(32) NOT NULL," +
                "plugin_name VARCHAR(64) NOT NULL," +
                "registered_at BIGINT NOT NULL," +
                "PRIMARY KEY (ns)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public InitialState loadInitialState() {
        Map<String, Integer> worlds = new LinkedHashMap<>();
        db.query("SELECT id, name FROM " + db.t("town_worlds"), rs -> {
            worlds.put(rs.getString("name"), rs.getInt("id"));
            return null;
        });

        List<Town> towns = db.query(
                "SELECT t.id, t.uuid, t.owner_uuid, t.name, t.world_id, w.name AS world_name, " +
                        "t.heart_cx, t.heart_cz, t.growth_points, t.status, t.created_at " +
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id",
                rs -> new Town(
                        rs.getLong("id"),
                        UuidBytes.fromBytes(rs.getBytes("uuid")),
                        UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                        rs.getString("name"),
                        rs.getString("world_name"),
                        rs.getInt("world_id"),
                        new ChunkPos(rs.getInt("heart_cx"), rs.getInt("heart_cz")),
                        rs.getInt("growth_points"),
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        TownStatus.valueOf(rs.getString("status"))
                )
        );

        List<ChunkRecord> chunks = db.query(
                "SELECT world_id, cx, cz, town_id, bucket_x, bucket_z FROM " + db.t("town_chunks"),
                rs -> new ChunkRecord(
                        rs.getInt("world_id"),
                        rs.getInt("cx"),
                        rs.getInt("cz"),
                        rs.getLong("town_id"),
                        rs.getInt("bucket_x"),
                        rs.getInt("bucket_z")
                )
        );

        List<MemberRecord> members = db.query(
                "SELECT uuid, town_id, role FROM " + db.t("town_members"),
                rs -> new MemberRecord(
                        UuidBytes.fromBytes(rs.getBytes("uuid")),
                        rs.getLong("town_id"),
                        TownRole.fromId(rs.getInt("role"))
                )
        );

        return new InitialState(worlds, towns, chunks, members);
    }

    public int getOrCreateWorldId(String worldName) {
        Optional<Integer> existing = db.queryOne(
                "SELECT id FROM " + db.t("town_worlds") + " WHERE name=?",
                rs -> rs.getInt("id"),
                worldName
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        db.update("INSERT INTO " + db.t("town_worlds") + " (name) VALUES (?) ON DUPLICATE KEY UPDATE name=VALUES(name)", worldName);
        return db.queryOne(
                "SELECT id FROM " + db.t("town_worlds") + " WHERE name=?",
                rs -> rs.getInt("id"),
                worldName
        ).orElseThrow();
    }

    public void createTown(Town town, List<ChunkPos> chunks, UUID ownerId, int bucketSize) {
        db.tx(tx -> {
            tx.update("INSERT INTO " + tx.t("towns") + " (id, uuid, owner_uuid, name, world_id, heart_cx, heart_cz, growth_points, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    town.internalId(), UuidBytes.toBytes(town.id()), UuidBytes.toBytes(ownerId), town.name(), town.worldId(),
                    town.heart().x(), town.heart().z(), town.growthPoints(), town.status().name(), town.createdAt().toEpochMilli());

            tx.update("INSERT INTO " + tx.t("town_members") + " (uuid, town_id, role, joined_at) VALUES (?, ?, ?, ?)",
                    UuidBytes.toBytes(ownerId), town.internalId(), TownRole.OWNER.id(), System.currentTimeMillis());

            List<Object[]> batch = new ArrayList<>();
            for (ChunkPos chunk : chunks) {
                batch.add(new Object[]{town.worldId(), chunk.x(), chunk.z(), town.internalId(), Math.floorDiv(chunk.x(), bucketSize), Math.floorDiv(chunk.z(), bucketSize)});
            }
            tx.batch("INSERT INTO " + tx.t("town_chunks") + " (world_id, cx, cz, town_id, bucket_x, bucket_z) VALUES (?, ?, ?, ?, ?, ?)", batch);
            return null;
        });
    }

    public boolean addChunkAndConsumeGrowth(Town town, ChunkPos chunk, int bucketSize) {
        return db.tx(tx -> {
            int changed = tx.update("UPDATE " + tx.t("towns") + " SET growth_points=growth_points-1 WHERE id=? AND growth_points>0", town.internalId());
            if (changed == 0) {
                return false;
            }
            tx.update("INSERT INTO " + tx.t("town_chunks") + " (world_id, cx, cz, town_id, bucket_x, bucket_z) VALUES (?, ?, ?, ?, ?, ?)",
                    town.worldId(), chunk.x(), chunk.z(), town.internalId(), Math.floorDiv(chunk.x(), bucketSize), Math.floorDiv(chunk.z(), bucketSize));
            return true;
        });
    }

    public void addMember(long townId, UUID playerId, TownRole role) {
        db.update("INSERT INTO " + db.t("town_members") + " (uuid, town_id, role, joined_at) VALUES (?, ?, ?, ?)",
                UuidBytes.toBytes(playerId), townId, role.id(), System.currentTimeMillis());
    }

    public void removeMember(UUID playerId) {
        db.update("DELETE FROM " + db.t("town_members") + " WHERE uuid=?", UuidBytes.toBytes(playerId));
    }

    public void destroyTown(long townId) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_meta") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_members") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_chunks") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("towns") + " WHERE id=?", townId);
            return null;
        });
    }

    public void updateTownStatus(long townId, TownStatus status) {
        db.update("UPDATE " + db.t("towns") + " SET status=? WHERE id=?", status.name(), townId);
    }

    public void renameTown(long townId, String name) {
        db.update("UPDATE " + db.t("towns") + " SET name=? WHERE id=?", name, townId);
    }


    public void addGrowth(long townId, int delta) {
        db.update("UPDATE " + db.t("towns") + " SET growth_points=growth_points+? WHERE id=?", delta, townId);
    }

    public void setGrowth(long townId, int amount) {
        db.update("UPDATE " + db.t("towns") + " SET growth_points=? WHERE id=?", amount, townId);
    }

    public Map<Long, Integer> loadGrowthPoints() {
        Map<Long, Integer> result = new LinkedHashMap<>();
        db.query("SELECT id, growth_points FROM " + db.t("towns"), rs -> {
            result.put(rs.getLong("id"), rs.getInt("growth_points"));
            return null;
        });
        return result;
    }

    public void upsertCoopRequest(long townId, UUID requester) {
        db.update("INSERT INTO " + db.t("town_coop_requests") + " (town_id, requester, created_at) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE created_at=VALUES(created_at)",
                townId, UuidBytes.toBytes(requester), System.currentTimeMillis());
    }

    public boolean hasCoopRequest(long townId, UUID requester, long maxAgeMillis) {
        long minCreated = System.currentTimeMillis() - maxAgeMillis;
        return db.queryOne("SELECT created_at FROM " + db.t("town_coop_requests") + " WHERE town_id=? AND requester=? AND created_at>=?",
                rs -> rs.getLong("created_at"), townId, UuidBytes.toBytes(requester), minCreated).isPresent();
    }

    public void deleteCoopRequest(long townId, UUID requester) {
        db.update("DELETE FROM " + db.t("town_coop_requests") + " WHERE town_id=? AND requester=?", townId, UuidBytes.toBytes(requester));
    }

    public String getMeta(long townId, String namespace, String key, String def) {
        return db.queryOne("SELECT v FROM " + db.t("town_meta") + " WHERE town_id=? AND ns=? AND k=?",
                rs -> rs.getString("v"), townId, namespace, key).orElse(def);
    }

    public Map<String, String> getMetaPrefix(long townId, String namespace, String keyPrefix) {
        Map<String, String> result = new LinkedHashMap<>();
        db.query("SELECT k, v FROM " + db.t("town_meta") + " WHERE town_id=? AND ns=? AND k LIKE ?",
                rs -> {
                    result.put(rs.getString("k"), rs.getString("v"));
                    return null;
                }, townId, namespace, keyPrefix + "%");
        return result;
    }

    public void setMeta(long townId, String namespace, String key, String value) {
        db.update("INSERT INTO " + db.t("town_meta") + " (town_id, ns, k, v) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE v=VALUES(v)",
                townId, namespace, key, value);
    }

    public void upsertNamespace(String namespace, String pluginName) {
        db.update("INSERT INTO " + db.t("town_data_namespaces") + " (ns, plugin_name, registered_at) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE plugin_name=VALUES(plugin_name), registered_at=VALUES(registered_at)",
                namespace, pluginName, System.currentTimeMillis());
    }

    public int countTowns() {
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("towns"), rs -> rs.getInt("c")).orElse(0);
    }

    public Page<Town> listPage(long afterInternalId, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<Town> towns = db.query(
                "SELECT t.id, t.uuid, t.owner_uuid, t.name, t.world_id, w.name AS world_name, " +
                        "t.heart_cx, t.heart_cz, t.growth_points, t.status, t.created_at " +
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id " +
                        "WHERE t.id>? ORDER BY t.id LIMIT ?",
                rs -> new Town(
                        rs.getLong("id"), UuidBytes.fromBytes(rs.getBytes("uuid")), UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                        rs.getString("name"), rs.getString("world_name"), rs.getInt("world_id"),
                        new ChunkPos(rs.getInt("heart_cx"), rs.getInt("heart_cz")), rs.getInt("growth_points"),
                        Instant.ofEpochMilli(rs.getLong("created_at")), TownStatus.valueOf(rs.getString("status"))),
                afterInternalId, capped);
        String next = towns.size() < capped ? null : String.valueOf(towns.get(towns.size() - 1).internalId());
        return new Page<>(towns, next);
    }

    public record InitialState(Map<String, Integer> worlds, List<Town> towns, List<ChunkRecord> chunks, List<MemberRecord> members) {
    }

    public record ChunkRecord(int worldId, int x, int z, long townId, int bucketX, int bucketZ) {
    }

    public record MemberRecord(UUID playerId, long townId, TownRole role) {
    }
}