package hex.towns.database;

import hex.core.api.db.Db;
import hex.towns.api.Page;
import hex.towns.api.TownPermission;
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

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_member_permissions") + " (" +
                "player_uuid BINARY(16) NOT NULL," +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "permission VARCHAR(32) NOT NULL," +
                "allowed BOOLEAN NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (player_uuid, permission)," +
                "KEY idx_town_permissions (town_id)" +
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

        try { db.update("ALTER TABLE " + db.t("town_coop_requests") + " ADD INDEX idx_created (created_at)"); } catch (RuntimeException ignored) { }

        ensureDataNamespaceTable();

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_cleanup_jobs") + " (" +
                "town_uuid BINARY(16) NOT NULL," +
                "internal_town_id BIGINT UNSIGNED NULL," +
                "owner_uuid BINARY(16) NULL," +
                "town_name VARCHAR(64) NULL," +
                "world_id SMALLINT UNSIGNED NOT NULL," +
                "world_name VARCHAR(64) NOT NULL," +
                "heart_cx INT NOT NULL," +
                "heart_cz INT NOT NULL," +
                "heart_world VARCHAR(64) NULL," +
                "heart_x INT NULL," +
                "heart_y INT NULL," +
                "heart_z INT NULL," +
                "state VARCHAR(32) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "retry_count INT NOT NULL DEFAULT 0," +
                "last_error TEXT NULL," +
                "bound_scan_cursor INT NOT NULL DEFAULT 0," +
                "PRIMARY KEY (town_uuid)," +
                "KEY idx_cleanup_state (state, updated_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        try { db.update("ALTER TABLE " + db.t("town_cleanup_jobs") + " ADD COLUMN bound_scan_cursor INT NOT NULL DEFAULT 0"); } catch (RuntimeException ignored) { }

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_cleanup_job_chunks") + " (" +
                "town_uuid BINARY(16) NOT NULL," +
                "world_id SMALLINT UNSIGNED NOT NULL," +
                "world_name VARCHAR(64) NOT NULL," +
                "chunk_x INT NOT NULL," +
                "chunk_z INT NOT NULL," +
                "PRIMARY KEY (town_uuid, world_name, chunk_x, chunk_z)," +
                "KEY idx_cleanup_chunk (world_name, chunk_x, chunk_z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_cleanup_job_members") + " (" +
                "town_uuid BINARY(16) NOT NULL," +
                "player_uuid BINARY(16) NOT NULL," +
                "reset_required BOOLEAN NOT NULL DEFAULT TRUE," +
                "reset_done BOOLEAN NOT NULL DEFAULT FALSE," +
                "PRIMARY KEY (town_uuid, player_uuid)," +
                "KEY idx_cleanup_member (player_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_cleanup_job_parts") + " (" +
                "town_uuid BINARY(16) NOT NULL," +
                "subsystem VARCHAR(64) NOT NULL," +
                "state VARCHAR(16) NOT NULL," +
                "retries INT NOT NULL DEFAULT 0," +
                "last_error TEXT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_uuid, subsystem)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_pending_player_resets") + " (" +
                "player_uuid BINARY(16) NOT NULL," +
                "town_uuid BINARY(16) NULL," +
                "reason VARCHAR(32) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "retry_count INT NOT NULL DEFAULT 0," +
                "last_error TEXT NULL," +
                "PRIMARY KEY (player_uuid)," +
                "KEY idx_pending_reset_created (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_audit_log") + " (" +
                "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                "town_uuid BINARY(16) NULL," +
                "player_uuid BINARY(16) NULL," +
                "action VARCHAR(64) NOT NULL," +
                "data VARCHAR(512) NULL," +
                "created_at BIGINT NOT NULL," +
                "PRIMARY KEY (id)," +
                "KEY idx_town_audit (town_uuid, created_at)," +
                "KEY idx_player_audit (player_uuid, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_heart_foundation_blocks") + " (" +
                "town_uuid BINARY(16) NOT NULL," +
                "world_name VARCHAR(64) NOT NULL," +
                "x INT NOT NULL," +
                "y INT NOT NULL," +
                "z INT NOT NULL," +
                "previous_material VARCHAR(64) NOT NULL," +
                "PRIMARY KEY (town_uuid, world_name, x, y, z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        ensureCoreTownConstraints();
    }

    /**
     * Adds cascade constraints as a final safety net when the existing dataset is already clean.
     * This method is intentionally non-destructive: historical orphan rows are surfaced by the
     * scanner and require an explicit repair command instead of being silently deleted at startup.
     */
    private void ensureCoreTownConstraints() {
        String constraintPrefix = "fk_" + Integer.toUnsignedString(db.tablePrefix().hashCode(), 16) + "_";
        ensureCascadeFk("town_chunks", constraintPrefix + "town_chunks_parent");
        ensureCascadeFk("town_members", constraintPrefix + "town_members_parent");
        ensureCascadeFk("town_member_permissions", constraintPrefix + "town_member_permissions_parent");
        ensureCascadeFk("town_meta", constraintPrefix + "town_meta_parent");
        ensureCascadeFk("town_coop_requests", constraintPrefix + "town_coop_requests_parent");
    }

    private void ensureCascadeFk(String childTable, String constraintName) {
        try {
            db.update("ALTER TABLE " + db.t(childTable) +
                    " ADD CONSTRAINT " + constraintName +
                    " FOREIGN KEY (town_id) REFERENCES " + db.t("towns") + "(id) ON DELETE CASCADE");
        } catch (RuntimeException ignored) {
            // Already present, unsupported by the current DB engine, or blocked by a legacy schema.
            // Startup must remain backwards-compatible; the explicit cleanup path is authoritative.
        }
    }

    public void audit(UUID townUuid, UUID playerUuid, String action, String data) {
        db.update("INSERT INTO " + db.t("town_audit_log") + " (town_uuid, player_uuid, action, data, created_at) VALUES (?,?,?,?,?)",
                townUuid == null ? null : UuidBytes.toBytes(townUuid),
                playerUuid == null ? null : UuidBytes.toBytes(playerUuid),
                action == null ? "UNKNOWN" : action,
                data == null ? "" : data,
                System.currentTimeMillis());
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
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id WHERE t.status IN ('ACTIVE','DESTROYING')",
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
                "SELECT c.world_id, c.cx, c.cz, c.town_id, c.bucket_x, c.bucket_z FROM " + db.t("town_chunks") + " c " +
                        "JOIN " + db.t("towns") + " t ON t.id=c.town_id WHERE t.status IN ('ACTIVE','DESTROYING')",
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
                "SELECT m.uuid, m.town_id, m.role FROM " + db.t("town_members") + " m " +
                        "JOIN " + db.t("towns") + " t ON t.id=m.town_id WHERE t.status IN ('ACTIVE','DESTROYING')",
                rs -> new MemberRecord(
                        UuidBytes.fromBytes(rs.getBytes("uuid")),
                        rs.getLong("town_id"),
                        TownRole.fromId(rs.getInt("role"))
                )
        );

        List<MemberPermissionRecord> permissions = db.query(
                "SELECT p.player_uuid, p.town_id, p.permission, p.allowed FROM " + db.t("town_member_permissions") + " p " +
                        "JOIN " + db.t("towns") + " t ON t.id=p.town_id WHERE t.status IN ('ACTIVE','DESTROYING')",
                rs -> {
                    try {
                        return new MemberPermissionRecord(UuidBytes.fromBytes(rs.getBytes("player_uuid")), rs.getLong("town_id"),
                                TownPermission.valueOf(rs.getString("permission")), rs.getBoolean("allowed"));
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                }
        ).stream().filter(java.util.Objects::nonNull).toList();

        return new InitialState(worlds, towns, chunks, members, permissions);
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

    /**
     * Atomically accepts a COOP request. The town row is locked first so two accepts for the
     * last free slot cannot both pass the capacity check. Runtime caches must only be updated
     * by the caller after SUCCESS has committed.
     */
    public AcceptCoopDbResult acceptCoopTransactional(long townId,
                                                       UUID townUuid,
                                                       UUID ownerId,
                                                       UUID requesterId,
                                                       int maxMembers,
                                                       long requestMaxAgeMillis,
                                                       Map<TownPermission, Boolean> permissionDefaults) {
        if (townUuid == null || ownerId == null || requesterId == null) return AcceptCoopDbResult.INVALID;
        int boundedMaxMembers = Math.max(1, maxMembers);
        long minCreatedAt = System.currentTimeMillis() - Math.max(1L, requestMaxAgeMillis);
        byte[] requesterBytes = UuidBytes.toBytes(requesterId);
        byte[] ownerBytes = UuidBytes.toBytes(ownerId);
        byte[] townUuidBytes = UuidBytes.toBytes(townUuid);

        return db.tx(tx -> {
            Optional<String> status = tx.queryOne(
                    "SELECT status FROM " + tx.t("towns") + " WHERE id=? AND uuid=? AND owner_uuid=? FOR UPDATE",
                    rs -> rs.getString("status"), townId, townUuidBytes, ownerBytes);
            if (status.isEmpty() || !TownStatus.ACTIVE.name().equalsIgnoreCase(status.get())) {
                return AcceptCoopDbResult.TOWN_INACTIVE;
            }

            Optional<Long> requestCreatedAt = tx.queryOne(
                    "SELECT created_at FROM " + tx.t("town_coop_requests") + " WHERE town_id=? AND requester=? FOR UPDATE",
                    rs -> rs.getLong("created_at"), townId, requesterBytes);
            if (requestCreatedAt.isEmpty() || requestCreatedAt.get() < minCreatedAt) {
                if (requestCreatedAt.isPresent()) {
                    tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE town_id=? AND requester=?", townId, requesterBytes);
                }
                return AcceptCoopDbResult.NO_REQUEST;
            }

            Optional<Long> existingTown = tx.queryOne(
                    "SELECT town_id FROM " + tx.t("town_members") + " WHERE uuid=? FOR UPDATE",
                    rs -> rs.getLong("town_id"), requesterBytes);
            if (existingTown.isPresent()) {
                tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE requester=?", requesterBytes);
                return AcceptCoopDbResult.REQUESTER_HAS_TOWN;
            }

            long memberCount = tx.queryOne(
                    "SELECT COUNT(*) AS c FROM " + tx.t("town_members") + " WHERE town_id=?",
                    rs -> rs.getLong("c"), townId).orElse(0L);
            if (memberCount >= boundedMaxMembers) return AcceptCoopDbResult.FULL;

            long now = System.currentTimeMillis();
            tx.update("INSERT INTO " + tx.t("town_members") + " (uuid, town_id, role, joined_at) VALUES (?, ?, ?, ?)",
                    requesterBytes, townId, TownRole.COOP.id(), now);

            if (permissionDefaults != null && !permissionDefaults.isEmpty()) {
                List<Object[]> permissions = new ArrayList<>();
                for (Map.Entry<TownPermission, Boolean> entry : permissionDefaults.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    permissions.add(new Object[]{requesterBytes, townId, entry.getKey().name(), entry.getValue(), now});
                }
                if (!permissions.isEmpty()) {
                    tx.batch("INSERT INTO " + tx.t("town_member_permissions") +
                                    " (player_uuid, town_id, permission, allowed, updated_at) VALUES (?, ?, ?, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE town_id=VALUES(town_id), allowed=VALUES(allowed), updated_at=VALUES(updated_at)",
                            permissions);
                }
            }

            tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE requester=?", requesterBytes);
            tx.update("INSERT INTO " + tx.t("town_audit_log") +
                            " (town_uuid, player_uuid, action, data, created_at) VALUES (?,?,?,?,?)",
                    townUuidBytes, ownerBytes, "TOWN_COOP_JOIN", "member=" + requesterId, now);
            return AcceptCoopDbResult.SUCCESS;
        });
    }

    public void addMember(long townId, UUID playerId, TownRole role) {
        db.update("INSERT INTO " + db.t("town_members") + " (uuid, town_id, role, joined_at) VALUES (?, ?, ?, ?)",
                UuidBytes.toBytes(playerId), townId, role.id(), System.currentTimeMillis());
    }

    public void setMemberPermission(long townId, UUID playerId, TownPermission permission, boolean allowed) {
        if (playerId == null || permission == null) return;
        db.update("INSERT INTO " + db.t("town_member_permissions") + " (player_uuid, town_id, permission, allowed, updated_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE town_id=VALUES(town_id), allowed=VALUES(allowed), updated_at=VALUES(updated_at)",
                UuidBytes.toBytes(playerId), townId, permission.name(), allowed, System.currentTimeMillis());
    }

    public void deleteMemberPermissions(UUID playerId) {
        if (playerId == null) return;
        db.update("DELETE FROM " + db.t("town_member_permissions") + " WHERE player_uuid=?", UuidBytes.toBytes(playerId));
    }

    public void removeMember(UUID playerId) {
        db.update("DELETE FROM " + db.t("town_members") + " WHERE uuid=?", UuidBytes.toBytes(playerId));
        deleteMemberPermissions(playerId);
    }

    /**
     * Czyści dane dostępu pojedynczego gracza odpinanego od miasta.
     * Nie usuwa danych globalnych miasta, takich jak kolekcje czy statystyki minionów całego miasta.
     */
    public void purgeDepartedMemberData(Town town, UUID playerId) {
        if (playerId == null) return;
        long townId = town == null ? -1L : town.internalId();
        UUID townUuid = town == null ? null : town.id();
        byte[] playerBytes = UuidBytes.toBytes(playerId);

        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("town_members") + " WHERE uuid=?", playerBytes);
            tx.update("DELETE FROM " + tx.t("town_member_permissions") + " WHERE player_uuid=?", playerBytes);
            tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE requester=?", playerBytes);
            if (townId > 0) {
                tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE town_id=? AND requester=?", townId, playerBytes);
            }
            return null;
        });

        // Starsze/alternatywne tabele oraz dane per-gracz z pluginów pobocznych.
        bestEffortUpdate("DELETE FROM " + db.t("town_ccop_requests") + " WHERE requester=?", playerBytes);
        bestEffortUpdate("DELETE FROM " + db.t("town_ccop_requests") + " WHERE requester=?", playerId.toString());
        bestEffortUpdate("DELETE FROM " + db.t("town_data_namespace") + " WHERE player_uuid=?", playerBytes);
        bestEffortUpdate("DELETE FROM " + db.t("town_data_namespace") + " WHERE player_uuid=?", playerId.toString());
        bestEffortUpdate("DELETE FROM " + db.t("town_data_namespace") + " WHERE uuid=?", playerBytes);
        bestEffortUpdate("DELETE FROM " + db.t("town_data_namespace") + " WHERE uuid=?", playerId.toString());
        if (townId > 0) {
            bestEffortUpdate("DELETE FROM " + db.t("town_ccop_requests") + " WHERE town_id=? AND requester=?", townId, playerBytes);
            bestEffortUpdate("DELETE FROM " + db.t("town_ccop_requests") + " WHERE town_id=? AND requester=?", townId, playerId.toString());
            bestEffortUpdate("DELETE FROM " + db.t("town_meta") + " WHERE town_id=? AND (k LIKE ? OR v LIKE ?)", townId, "%" + playerId + "%", "%" + playerId + "%");
            bestEffortUpdate("DELETE FROM " + db.t("minion_audit_log") + " WHERE town_id=? AND actor_uuid=?", townId, playerBytes);
        }
        if (townUuid != null) {
            bestEffortUpdate("DELETE FROM " + db.t("collection_events") + " WHERE town_id=? AND player_uuid=?", townUuid.toString(), playerId.toString());
        }
    }

    /**
     * Idempotent core-only delete. Dependent plugin data is deliberately not touched here;
     * each registered namespace owns its own purge and failures are retried from the cleanup job.
     */
    public void destroyTownCore(Town town) {
        if (town == null) return;
        destroyTownCore(town.internalId(), town.worldId());
    }

    public void destroyTownCore(long townId, int worldId) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_meta") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_member_permissions") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_members") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_chunks") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("towns") + " WHERE id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_worlds") + " WHERE id=? AND NOT EXISTS (SELECT 1 FROM " + tx.t("towns") + " WHERE world_id=?)", worldId, worldId);
            return null;
        });
    }

    /** Compatibility alias retained for older callers. */
    public void destroyTown(Town town) {
        destroyTownCore(town);
    }

    /** Compatibility alias retained for older callers. */
    public void destroyTown(long townId) {
        Optional<Town> town = findTownRecordByInternalId(townId);
        if (town.isPresent()) destroyTownCore(town.get());
        else {
            db.tx(tx -> {
                tx.update("DELETE FROM " + tx.t("town_coop_requests") + " WHERE town_id=?", townId);
                tx.update("DELETE FROM " + tx.t("town_meta") + " WHERE town_id=?", townId);
                tx.update("DELETE FROM " + tx.t("town_members") + " WHERE town_id=?", townId);
                tx.update("DELETE FROM " + tx.t("town_chunks") + " WHERE town_id=?", townId);
                tx.update("DELETE FROM " + tx.t("towns") + " WHERE id=?", townId);
                return null;
            });
        }
    }

    public Optional<Town> findTownRecordByInternalId(long townId) {
        return db.queryOne(
                "SELECT t.id, t.uuid, t.owner_uuid, t.name, t.world_id, w.name AS world_name, " +
                        "t.heart_cx, t.heart_cz, t.growth_points, t.status, t.created_at " +
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id WHERE t.id=?",
                rs -> mapTown(rs), townId);
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
        db.query("SELECT id, growth_points FROM " + db.t("towns") + " WHERE status='ACTIVE'", rs -> {
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

    public List<CoopRequestRecord> listCoopRequests(long townId, long maxAgeMillis, int limit) {
        long minCreated = System.currentTimeMillis() - maxAgeMillis;
        int capped = Math.max(1, Math.min(limit, 100));
        return db.query("SELECT requester, created_at FROM " + db.t("town_coop_requests") + " WHERE town_id=? AND created_at>=? ORDER BY created_at ASC LIMIT ?",
                rs -> new CoopRequestRecord(UuidBytes.fromBytes(rs.getBytes("requester")), rs.getLong("created_at")),
                townId, minCreated, capped);
    }

    public Optional<MemberRecord> findMemberRecord(UUID playerId) {
        if (playerId == null) return Optional.empty();
        return db.queryOne("SELECT uuid, town_id, role FROM " + db.t("town_members") + " WHERE uuid=?",
                rs -> new MemberRecord(UuidBytes.fromBytes(rs.getBytes("uuid")), rs.getLong("town_id"), TownRole.fromId(rs.getInt("role"))),
                UuidBytes.toBytes(playerId));
    }

    public List<CoopRequestDebugRecord> listCoopRequestsForRequester(UUID requester, int limit) {
        if (requester == null) return List.of();
        int capped = Math.max(1, Math.min(limit, 100));
        return db.query("SELECT town_id, created_at FROM " + db.t("town_coop_requests") + " WHERE requester=? ORDER BY created_at DESC LIMIT ?",
                rs -> new CoopRequestDebugRecord(rs.getLong("town_id"), rs.getLong("created_at")),
                UuidBytes.toBytes(requester), capped);
    }

    public void deleteAllCoopRequestsForRequester(UUID requester) {
        db.update("DELETE FROM " + db.t("town_coop_requests") + " WHERE requester=?", UuidBytes.toBytes(requester));
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

    public void ensureDataNamespaceTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_data_namespaces") + " (" +
                "ns VARCHAR(32) NOT NULL," +
                "plugin_name VARCHAR(64) NOT NULL," +
                "registered_at BIGINT NOT NULL," +
                "active BOOLEAN NOT NULL DEFAULT TRUE," +
                "last_seen_at BIGINT NULL," +
                "plugin_version VARCHAR(32) NULL," +
                "retired_at BIGINT NULL," +
                "PRIMARY KEY (ns)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        // Backward-compatible schema upgrades. Old installations can have only the original 3 columns.
        try { db.update("ALTER TABLE " + db.t("town_data_namespaces") + " ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("town_data_namespaces") + " ADD COLUMN last_seen_at BIGINT NULL"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("town_data_namespaces") + " ADD COLUMN plugin_version VARCHAR(32) NULL"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("town_data_namespaces") + " ADD COLUMN retired_at BIGINT NULL"); } catch (RuntimeException ignored) { }
    }

    public void upsertNamespace(String namespace, String pluginName) {
        upsertNamespace(namespace, pluginName, null);
    }

    public void upsertNamespace(String namespace, String pluginName, String pluginVersion) {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO " + db.t("town_data_namespaces") + " (ns, plugin_name, registered_at, active, last_seen_at, plugin_version, retired_at) VALUES (?, ?, ?, TRUE, ?, ?, NULL) " +
                        "ON DUPLICATE KEY UPDATE plugin_name=VALUES(plugin_name), active=TRUE, last_seen_at=VALUES(last_seen_at), plugin_version=VALUES(plugin_version), retired_at=NULL",
                namespace, pluginName, now, now, pluginVersion);
    }

    /** Historical namespace registry; not used to construct new cleanup jobs. */
    public List<String> registeredNamespaces() {
        return db.query("SELECT ns FROM " + db.t("town_data_namespaces") + " ORDER BY ns", rs -> rs.getString("ns"));
    }

    public boolean isNamespaceActive(String namespace) {
        if (namespace == null || namespace.isBlank()) return false;
        try {
            return db.queryOne("SELECT active FROM " + db.t("town_data_namespaces") + " WHERE ns=?", rs -> rs.getBoolean("active"), namespace).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** True only for a registry row that an admin explicitly marked inactive/retired. Missing metadata is not retirement. */
    public boolean isNamespaceRetired(String namespace) {
        if (namespace == null || namespace.isBlank()) return false;
        try {
            return db.queryOne("SELECT active FROM " + db.t("town_data_namespaces") + " WHERE ns=?", rs -> !rs.getBoolean("active"), namespace).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public boolean setNamespaceActive(String namespace, boolean active) {
        if (namespace == null || namespace.isBlank()) return false;
        long now = System.currentTimeMillis();
        return db.update("UPDATE " + db.t("town_data_namespaces") + " SET active=?, retired_at=?, last_seen_at=CASE WHEN ? THEN ? ELSE last_seen_at END WHERE ns=?",
                active, active ? null : now, active, now, namespace) > 0;
    }

    public List<NamespaceRegistration> namespaceRegistrations() {
        return db.query("SELECT ns, plugin_name, registered_at, active, last_seen_at, plugin_version, retired_at FROM " + db.t("town_data_namespaces") + " ORDER BY ns",
                rs -> {
                    Object retiredRaw = rs.getObject("retired_at");
                    Long retiredAt = retiredRaw == null ? null : rs.getLong("retired_at");
                    return new NamespaceRegistration(rs.getString("ns"), rs.getString("plugin_name"), rs.getLong("registered_at"), rs.getBoolean("active"),
                            rs.getLong("last_seen_at"), rs.getString("plugin_version"), retiredAt);
                });
    }

    /** Read-only relational/legacy scanner used by /townadmin cleanup scan-orphans. */
    public OrphanScanReport scanOrphans(java.util.Set<String> runtimeNamespaces) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        scanCount(counts, "town_chunks_without_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_chunks") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", "town_chunks", "towns");
        scanCount(counts, "town_members_without_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_members") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", "town_members", "towns");
        scanCount(counts, "town_permissions_without_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_member_permissions") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", "town_member_permissions", "towns");
        scanCount(counts, "town_meta_without_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_meta") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", "town_meta", "towns");
        scanCount(counts, "town_coop_without_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_coop_requests") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", "town_coop_requests", "towns");

        scanCount(counts, "minion_storage_without_minion",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_storage") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", "minion_storage", "minions");
        scanCount(counts, "minion_drop_progress_without_minion",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_drop_progress") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", "minion_drop_progress", "minions");
        scanCount(counts, "minion_upgrades_without_minion",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_upgrades") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", "minion_upgrades", "minions");
        scanCount(counts, "town_minion_stats_without_live_or_pending_town",
                "SELECT COUNT(*) AS c FROM " + db.t("town_minion_stats") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id " +
                        "LEFT JOIN " + db.t("town_cleanup_jobs") + " j ON j.internal_town_id=x.town_id AND j.state<>'DONE' WHERE t.id IS NULL AND j.internal_town_id IS NULL",
                "town_minion_stats", "towns", "town_cleanup_jobs");
        scanCount(counts, "minion_audit_without_live_or_pending_town",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_audit_log") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id " +
                        "LEFT JOIN " + db.t("town_cleanup_jobs") + " j ON j.internal_town_id=x.town_id AND j.state<>'DONE' WHERE t.id IS NULL AND j.internal_town_id IS NULL",
                "minion_audit_log", "towns", "town_cleanup_jobs");

        scanCount(counts, "energy_cable_chunks_without_cable",
                "SELECT COUNT(*) AS c FROM " + db.t("energy_cable_chunks") + " x LEFT JOIN " + db.t("energy_cables") + " c ON c.id=x.cable_id WHERE c.id IS NULL",
                "energy_cable_chunks", "energy_cables");
        scanCount(counts, "machine_runtimes_null_owner",
                "SELECT COUNT(*) AS c FROM " + db.t("machine_runtimes") + " WHERE town_uuid IS NULL", "machine_runtimes");
        scanCount(counts, "energy_cables_null_owner",
                "SELECT COUNT(*) AS c FROM " + db.t("energy_cables") + " WHERE town_uuid IS NULL", "energy_cables");

        scanCollectionOrphans(counts, "collection_progress_without_live_or_pending_town", "collection_progress");
        scanCollectionOrphans(counts, "collection_events_without_live_or_pending_town", "collection_events");
        scanCollectionOrphans(counts, "collection_scaling_without_live_or_pending_town", "collection_scaling");

        java.util.Set<String> activeHandlers = runtimeNamespaces == null ? java.util.Set.of() : runtimeNamespaces;
        int staleNamespaces = 0;
        for (NamespaceRegistration registration : namespaceRegistrations()) {
            if (registration.active() && !activeHandlers.contains(registration.namespace())) {
                staleNamespaces++;
                warnings.add("active namespace without runtime handler: " + registration.namespace() + " (plugin=" + registration.pluginName() + ")");
            }
        }
        counts.put("active_namespaces_without_handler", staleNamespaces);

        try {
            List<String> physicalTownTables = db.query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('towns','hex_towns','town_worlds','hex_town_worlds') ORDER BY table_name",
                    rs -> rs.getString("table_name"));
            boolean hasPlain = physicalTownTables.stream().anyMatch(name -> name.equals("towns") || name.equals("town_worlds"));
            boolean hasHex = physicalTownTables.stream().anyMatch(name -> name.equals("hex_towns") || name.equals("hex_town_worlds"));
            if (hasPlain && hasHex) {
                warnings.add("multiple town table prefixes detected: " + String.join(",", physicalTownTables) + "; active prefix='" + db.tablePrefix() + "'");
            }
        } catch (RuntimeException ex) {
            warnings.add("prefix-drift check failed: " + ex.getMessage());
        }

        return new OrphanScanReport(Map.copyOf(counts), List.copyOf(warnings), db.tablePrefix());
    }

    /**
     * Explicit repair for relations whose parent/child ownership is mathematically unambiguous.
     * Ambiguous legacy rows (NULL-owner machines/cables and collections without a live town) are
     * never touched here. The command layer defaults to dry-run and requires --apply to mutate DB.
     */
    public OrphanRepairReport repairSafeOrphans(boolean apply) {
        Map<String, Integer> affected = new LinkedHashMap<>();
        repairOrCount(affected, "town_chunks_without_town", "town_chunks", "towns",
                "SELECT COUNT(*) AS c FROM " + db.t("town_chunks") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL",
                "DELETE x FROM " + db.t("town_chunks") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", apply);
        repairOrCount(affected, "town_members_without_town", "town_members", "towns",
                "SELECT COUNT(*) AS c FROM " + db.t("town_members") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL",
                "DELETE x FROM " + db.t("town_members") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", apply);
        repairOrCount(affected, "town_permissions_without_town", "town_member_permissions", "towns",
                "SELECT COUNT(*) AS c FROM " + db.t("town_member_permissions") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL",
                "DELETE x FROM " + db.t("town_member_permissions") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", apply);
        repairOrCount(affected, "town_meta_without_town", "town_meta", "towns",
                "SELECT COUNT(*) AS c FROM " + db.t("town_meta") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL",
                "DELETE x FROM " + db.t("town_meta") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", apply);
        repairOrCount(affected, "town_coop_without_town", "town_coop_requests", "towns",
                "SELECT COUNT(*) AS c FROM " + db.t("town_coop_requests") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL",
                "DELETE x FROM " + db.t("town_coop_requests") + " x LEFT JOIN " + db.t("towns") + " t ON t.id=x.town_id WHERE t.id IS NULL", apply);

        repairOrCount(affected, "minion_storage_without_minion", "minion_storage", "minions",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_storage") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL",
                "DELETE x FROM " + db.t("minion_storage") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", apply);
        repairOrCount(affected, "minion_drop_progress_without_minion", "minion_drop_progress", "minions",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_drop_progress") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL",
                "DELETE x FROM " + db.t("minion_drop_progress") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", apply);
        repairOrCount(affected, "minion_upgrades_without_minion", "minion_upgrades", "minions",
                "SELECT COUNT(*) AS c FROM " + db.t("minion_upgrades") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL",
                "DELETE x FROM " + db.t("minion_upgrades") + " x LEFT JOIN " + db.t("minions") + " m ON m.id=x.minion_id WHERE m.id IS NULL", apply);
        repairOrCount(affected, "energy_cable_chunks_without_cable", "energy_cable_chunks", "energy_cables",
                "SELECT COUNT(*) AS c FROM " + db.t("energy_cable_chunks") + " x LEFT JOIN " + db.t("energy_cables") + " c ON c.id=x.cable_id WHERE c.id IS NULL",
                "DELETE x FROM " + db.t("energy_cable_chunks") + " x LEFT JOIN " + db.t("energy_cables") + " c ON c.id=x.cable_id WHERE c.id IS NULL", apply);

        if (apply) ensureCoreTownConstraints();
        return new OrphanRepairReport(Map.copyOf(affected), apply);
    }

    private void repairOrCount(Map<String, Integer> affected, String key, String table, String parentTable,
                               String countSql, String deleteSql, boolean apply) {
        if (!tableExists(table) || !tableExists(parentTable)) return;
        try {
            int count = db.queryOne(countSql, rs -> rs.getInt("c")).orElse(0);
            if (apply && count > 0) affected.put(key, db.update(deleteSql));
            else affected.put(key, count);
        } catch (RuntimeException ex) {
            affected.put(key + "_repair_failed", -1);
        }
    }

    private void scanCollectionOrphans(Map<String, Integer> counts, String key, String table) {
        if (!tableExists(table) || !tableExists("towns") || !tableExists("town_cleanup_jobs")) return;
        try {
            String physical = db.t(table);
            int value = db.queryOne("SELECT COUNT(*) AS c FROM " + physical + " x LEFT JOIN " + db.t("towns") +
                            " t ON t.uuid=UNHEX(REPLACE(x.town_id,'-','')) LEFT JOIN " + db.t("town_cleanup_jobs") +
                            " j ON j.town_uuid=UNHEX(REPLACE(x.town_id,'-','')) AND j.state<>'DONE' WHERE t.id IS NULL AND j.town_uuid IS NULL",
                    rs -> rs.getInt("c")).orElse(0);
            counts.put(key, value);
        } catch (RuntimeException ex) {
            counts.put(key + "_scan_failed", -1);
        }
    }

    private void scanCount(Map<String, Integer> counts, String key, String sql, String... logicalTables) {
        for (String table : logicalTables) if (!tableExists(table)) return;
        try {
            counts.put(key, db.queryOne(sql, rs -> rs.getInt("c")).orElse(0));
        } catch (RuntimeException ex) {
            counts.put(key + "_scan_failed", -1);
        }
    }

    private boolean tableExists(String logicalTable) {
        try {
            return db.queryOne("SELECT 1 AS ok FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=? LIMIT 1",
                    rs -> rs.getInt("ok"), db.t(logicalTable)).isPresent();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public int countTowns() {
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("towns") + " WHERE status='ACTIVE'", rs -> rs.getInt("c")).orElse(0);
    }

    public Page<Town> listPage(long afterInternalId, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<Town> towns = db.query(
                "SELECT t.id, t.uuid, t.owner_uuid, t.name, t.world_id, w.name AS world_name, " +
                        "t.heart_cx, t.heart_cz, t.growth_points, t.status, t.created_at " +
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id " +
                        "WHERE t.status='ACTIVE' AND t.id>? ORDER BY t.id LIMIT ?",
                rs -> new Town(
                        rs.getLong("id"), UuidBytes.fromBytes(rs.getBytes("uuid")), UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                        rs.getString("name"), rs.getString("world_name"), rs.getInt("world_id"),
                        new ChunkPos(rs.getInt("heart_cx"), rs.getInt("heart_cz")), rs.getInt("growth_points"),
                        Instant.ofEpochMilli(rs.getLong("created_at")), TownStatus.valueOf(rs.getString("status"))),
                afterInternalId, capped);
        String next = towns.size() < capped ? null : String.valueOf(towns.get(towns.size() - 1).internalId());
        return new Page<>(towns, next);
    }

    public CleanupJob beginDestroyJob(Town town, List<ChunkPos> chunks, List<UUID> members, List<String> requiredNamespaces) {
        if (town == null) throw new IllegalArgumentException("town");
        List<ChunkPos> chunkSnapshot = chunks == null ? List.of() : List.copyOf(chunks);
        List<UUID> memberSnapshot = members == null ? List.of() : List.copyOf(members);
        List<String> namespaces = requiredNamespaces == null ? List.of() : requiredNamespaces.stream().filter(java.util.Objects::nonNull).distinct().toList();
        long now = System.currentTimeMillis();

        return db.tx(tx -> {
            int changed = tx.update("UPDATE " + tx.t("towns") + " SET status='DESTROYING' WHERE id=? AND status='ACTIVE'", town.internalId());
            if (changed != 1) throw new IllegalStateException("Town is not ACTIVE or destroy already started: " + town.id());
            insertCleanupJobSnapshot(tx, town, chunkSnapshot, memberSnapshot, namespaces, now);
            return loadCleanupJob(tx, town.id()).orElseThrow();
        });
    }

    public void recoverDestroyingJobs(List<String> requiredNamespaces) {
        List<Town> destroying = db.query(
                "SELECT t.id, t.uuid, t.owner_uuid, t.name, t.world_id, w.name AS world_name, " +
                        "t.heart_cx, t.heart_cz, t.growth_points, t.status, t.created_at " +
                        "FROM " + db.t("towns") + " t JOIN " + db.t("town_worlds") + " w ON w.id=t.world_id WHERE t.status='DESTROYING'",
                this::mapTown);
        for (Town town : destroying) {
            if (cleanupJobExists(town.id())) {
                // Existing cleanup jobs own a durable namespace snapshot. Never append namespaces
                // discovered in a later boot, otherwise retries cease to be deterministic.
                ensureCleanupParts(town.id(), null);
                continue;
            }
            List<ChunkPos> chunks = db.query("SELECT cx, cz FROM " + db.t("town_chunks") + " WHERE town_id=?",
                    rs -> new ChunkPos(rs.getInt("cx"), rs.getInt("cz")), town.internalId());
            List<UUID> members = db.query("SELECT uuid FROM " + db.t("town_members") + " WHERE town_id=?",
                    rs -> UuidBytes.fromBytes(rs.getBytes("uuid")), town.internalId());
            if (!members.contains(town.ownerId())) {
                List<UUID> withOwner = new ArrayList<>(members);
                withOwner.add(town.ownerId());
                members = withOwner;
            }
            List<UUID> finalMembers = List.copyOf(members);
            long now = System.currentTimeMillis();
            db.tx(tx -> {
                insertCleanupJobSnapshot(tx, town, chunks, finalMembers, requiredNamespaces, now);
                return null;
            });
        }
    }

    private void insertCleanupJobSnapshot(Db tx, Town town, List<ChunkPos> chunks, List<UUID> members, List<String> requiredNamespaces, long now) {
        byte[] townBytes = UuidBytes.toBytes(town.id());
        String heartWorld = metaValue(tx, town.internalId(), "heart", "world", town.world());
        Integer heartX = parseNullableInt(metaValue(tx, town.internalId(), "heart", "x", null));
        Integer heartY = parseNullableInt(metaValue(tx, town.internalId(), "heart", "y", null));
        Integer heartZ = parseNullableInt(metaValue(tx, town.internalId(), "heart", "z", null));

        tx.update("INSERT INTO " + tx.t("town_cleanup_jobs") + " (town_uuid, internal_town_id, owner_uuid, town_name, world_id, world_name, heart_cx, heart_cz, heart_world, heart_x, heart_y, heart_z, state, created_at, updated_at, retry_count, last_error, bound_scan_cursor) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DESTROYING', ?, ?, 0, NULL, 0) " +
                        "ON DUPLICATE KEY UPDATE internal_town_id=VALUES(internal_town_id), owner_uuid=VALUES(owner_uuid), town_name=VALUES(town_name), world_id=VALUES(world_id), world_name=VALUES(world_name), heart_cx=VALUES(heart_cx), heart_cz=VALUES(heart_cz), heart_world=COALESCE(VALUES(heart_world), heart_world), heart_x=COALESCE(VALUES(heart_x), heart_x), heart_y=COALESCE(VALUES(heart_y), heart_y), heart_z=COALESCE(VALUES(heart_z), heart_z), updated_at=VALUES(updated_at)",
                townBytes, town.internalId(), UuidBytes.toBytes(town.ownerId()), town.name(), town.worldId(), town.world(), town.heart().x(), town.heart().z(),
                heartWorld, heartX, heartY, heartZ, now, now);

        for (ChunkPos chunk : chunks) {
            tx.update("INSERT IGNORE INTO " + tx.t("town_cleanup_job_chunks") + " (town_uuid, world_id, world_name, chunk_x, chunk_z) VALUES (?, ?, ?, ?, ?)",
                    townBytes, town.worldId(), town.world(), chunk.x(), chunk.z());
        }
        for (UUID member : members) {
            if (member == null) continue;
            byte[] playerBytes = UuidBytes.toBytes(member);
            tx.update("INSERT IGNORE INTO " + tx.t("town_cleanup_job_members") + " (town_uuid, player_uuid, reset_required, reset_done) VALUES (?, ?, TRUE, FALSE)", townBytes, playerBytes);
            tx.update("INSERT INTO " + tx.t("town_pending_player_resets") + " (player_uuid, town_uuid, reason, created_at, retry_count, last_error) VALUES (?, ?, 'DESTROY', ?, 0, NULL) " +
                            "ON DUPLICATE KEY UPDATE town_uuid=VALUES(town_uuid), reason=VALUES(reason)",
                    playerBytes, townBytes, now);
        }
        ensureCleanupPart(tx, town.id(), "CORE_DB", "PENDING");
        ensureCleanupPart(tx, town.id(), "BOUND_STORAGE", "PENDING");
        ensureCleanupPart(tx, town.id(), "WORLD", "PENDING");
        ensureCleanupPart(tx, town.id(), "PLAYERS", "DONE");
        if (requiredNamespaces != null) {
            for (String namespace : requiredNamespaces) {
                if (namespace == null || namespace.isBlank()) continue;
                ensureCleanupPart(tx, town.id(), "NS:" + namespace.toLowerCase(java.util.Locale.ROOT), "PENDING");
            }
        }
    }

    public void ensureCleanupParts(UUID townUuid, List<String> namespaces) {
        if (townUuid == null) return;
        db.tx(tx -> {
            ensureCleanupPart(tx, townUuid, "CORE_DB", "PENDING");
            ensureCleanupPart(tx, townUuid, "BOUND_STORAGE", "PENDING");
            ensureCleanupPart(tx, townUuid, "WORLD", "PENDING");
            ensureCleanupPart(tx, townUuid, "PLAYERS", "DONE");
            if (namespaces != null) {
                for (String namespace : namespaces) {
                    if (namespace != null && !namespace.isBlank()) ensureCleanupPart(tx, townUuid, "NS:" + namespace.toLowerCase(java.util.Locale.ROOT), "PENDING");
                }
            }
            return null;
        });
    }

    private void ensureCleanupPart(Db tx, UUID townUuid, String subsystem, String state) {
        tx.update("INSERT IGNORE INTO " + tx.t("town_cleanup_job_parts") + " (town_uuid, subsystem, state, retries, last_error, updated_at) VALUES (?, ?, ?, 0, NULL, ?)",
                UuidBytes.toBytes(townUuid), subsystem, state, System.currentTimeMillis());
    }

    public boolean cleanupJobExists(UUID townUuid) {
        if (townUuid == null) return false;
        return db.queryOne("SELECT 1 FROM " + db.t("town_cleanup_jobs") + " WHERE town_uuid=?", rs -> 1, UuidBytes.toBytes(townUuid)).isPresent();
    }

    public List<CleanupJob> loadPendingCleanupJobs() {
        List<UUID> ids = db.query("SELECT town_uuid FROM " + db.t("town_cleanup_jobs") + " WHERE state<>'DONE' ORDER BY updated_at ASC",
                rs -> UuidBytes.fromBytes(rs.getBytes("town_uuid")));
        List<CleanupJob> jobs = new ArrayList<>();
        for (UUID id : ids) loadCleanupJob(id).ifPresent(jobs::add);
        return jobs;
    }

    public Optional<CleanupJob> loadCleanupJob(UUID townUuid) {
        return loadCleanupJob(db, townUuid);
    }

    private Optional<CleanupJob> loadCleanupJob(Db source, UUID townUuid) {
        if (townUuid == null) return Optional.empty();
        byte[] townBytes = UuidBytes.toBytes(townUuid);
        Optional<CleanupJobBase> base = source.queryOne("SELECT town_uuid, internal_town_id, owner_uuid, town_name, world_id, world_name, heart_cx, heart_cz, heart_world, heart_x, heart_y, heart_z, state, created_at, updated_at, retry_count, last_error, bound_scan_cursor FROM " + source.t("town_cleanup_jobs") + " WHERE town_uuid=?",
                rs -> new CleanupJobBase(
                        UuidBytes.fromBytes(rs.getBytes("town_uuid")),
                        rs.getLong("internal_town_id"),
                        UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                        rs.getString("town_name"), rs.getInt("world_id"), rs.getString("world_name"),
                        rs.getInt("heart_cx"), rs.getInt("heart_cz"), rs.getString("heart_world"),
                        nullableInt(rs, "heart_x"), nullableInt(rs, "heart_y"), nullableInt(rs, "heart_z"),
                        rs.getString("state"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getInt("retry_count"), rs.getString("last_error"), rs.getInt("bound_scan_cursor")
                ), townBytes);
        if (base.isEmpty()) return Optional.empty();
        CleanupJobBase b = base.get();
        List<ChunkPos> chunks = source.query("SELECT chunk_x, chunk_z FROM " + source.t("town_cleanup_job_chunks") + " WHERE town_uuid=? ORDER BY chunk_x, chunk_z",
                rs -> new ChunkPos(rs.getInt("chunk_x"), rs.getInt("chunk_z")), townBytes);
        List<UUID> members = source.query("SELECT player_uuid FROM " + source.t("town_cleanup_job_members") + " WHERE town_uuid=?",
                rs -> UuidBytes.fromBytes(rs.getBytes("player_uuid")), townBytes);
        Map<String, CleanupPart> parts = new LinkedHashMap<>();
        source.query("SELECT subsystem, state, retries, last_error, updated_at FROM " + source.t("town_cleanup_job_parts") + " WHERE town_uuid=?",
                rs -> {
                    CleanupPart part = new CleanupPart(rs.getString("subsystem"), rs.getString("state"), rs.getInt("retries"), rs.getString("last_error"), rs.getLong("updated_at"));
                    parts.put(part.subsystem(), part);
                    return null;
                }, townBytes);
        Town town = new Town(b.internalTownId(), b.townUuid(), b.ownerUuid(), b.townName() == null ? "Destroyed town" : b.townName(),
                b.worldName(), b.worldId(), new ChunkPos(b.heartCx(), b.heartCz()), 0, Instant.ofEpochMilli(b.createdAt()), TownStatus.DESTROYING);
        return Optional.of(new CleanupJob(town, b.state(), b.heartWorld(), b.heartX(), b.heartY(), b.heartZ(), List.copyOf(chunks), List.copyOf(members), Map.copyOf(parts), b.retryCount(), b.lastError(), b.createdAt(), b.updatedAt(), b.boundScanCursor()));
    }

    public void updateBoundScanCursor(UUID townUuid, int cursor) {
        db.update("UPDATE " + db.t("town_cleanup_jobs") + " SET bound_scan_cursor=?, updated_at=? WHERE town_uuid=?",
                Math.max(0, cursor), System.currentTimeMillis(), UuidBytes.toBytes(townUuid));
    }

    public void resetBoundScanCursor(UUID townUuid) {
        updateBoundScanCursor(townUuid, 0);
    }

    public void markCleanupState(UUID townUuid, String state, String error) {
        db.update("UPDATE " + db.t("town_cleanup_jobs") + " SET state=?, updated_at=?, last_error=? WHERE town_uuid=?",
                state, System.currentTimeMillis(), error, UuidBytes.toBytes(townUuid));
    }

    public void markCleanupPart(UUID townUuid, String subsystem, boolean success, String error) {
        String state = success ? "DONE" : "FAILED";
        db.update("INSERT INTO " + db.t("town_cleanup_job_parts") + " (town_uuid, subsystem, state, retries, last_error, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE state=VALUES(state), retries=retries+?, last_error=VALUES(last_error), updated_at=VALUES(updated_at)",
                UuidBytes.toBytes(townUuid), subsystem, state, success ? 0 : 1, error, System.currentTimeMillis(), success ? 0 : 1);
    }

    public void noteCleanupRetry(UUID townUuid, String error) {
        db.update("UPDATE " + db.t("town_cleanup_jobs") + " SET retry_count=retry_count+1, updated_at=?, last_error=? WHERE town_uuid=?",
                System.currentTimeMillis(), error, UuidBytes.toBytes(townUuid));
    }

    public boolean markCleanupDoneIfComplete(UUID townUuid) {
        int pending = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("town_cleanup_job_parts") + " WHERE town_uuid=? AND state<>'DONE'",
                rs -> rs.getInt("c"), UuidBytes.toBytes(townUuid)).orElse(1);
        if (pending != 0) return false;
        markCleanupState(townUuid, "DONE", null);
        return true;
    }

    public int cleanupPendingCount() {
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("town_cleanup_jobs") + " WHERE state<>'DONE'", rs -> rs.getInt("c")).orElse(0);
    }

    public List<CleanupJobSummary> cleanupJobSummaries(int limit) {
        int capped = Math.max(1, Math.min(100, limit));
        return db.query("SELECT town_uuid, state, retry_count, updated_at, last_error FROM " + db.t("town_cleanup_jobs") + " ORDER BY updated_at DESC LIMIT ?",
                rs -> new CleanupJobSummary(UuidBytes.fromBytes(rs.getBytes("town_uuid")), rs.getString("state"), rs.getInt("retry_count"), rs.getLong("updated_at"), rs.getString("last_error")), capped);
    }

    public void enqueuePendingPlayerReset(UUID playerUuid, UUID townUuid, String reason) {
        if (playerUuid == null) return;
        db.update("INSERT INTO " + db.t("town_pending_player_resets") + " (player_uuid, town_uuid, reason, created_at, retry_count, last_error) VALUES (?, ?, ?, ?, 0, NULL) " +
                        "ON DUPLICATE KEY UPDATE town_uuid=VALUES(town_uuid), reason=VALUES(reason)",
                UuidBytes.toBytes(playerUuid), townUuid == null ? null : UuidBytes.toBytes(townUuid), normalizeReason(reason), System.currentTimeMillis());
    }

    public Optional<PendingPlayerReset> pendingPlayerReset(UUID playerUuid) {
        if (playerUuid == null) return Optional.empty();
        return db.queryOne("SELECT player_uuid, town_uuid, reason, created_at, retry_count, last_error FROM " + db.t("town_pending_player_resets") + " WHERE player_uuid=?",
                rs -> new PendingPlayerReset(UuidBytes.fromBytes(rs.getBytes("player_uuid")), uuidOrNull(rs.getBytes("town_uuid")), rs.getString("reason"), rs.getLong("created_at"), rs.getInt("retry_count"), rs.getString("last_error")),
                UuidBytes.toBytes(playerUuid));
    }

    public void completePendingPlayerReset(UUID playerUuid) {
        if (playerUuid == null) return;
        db.update("DELETE FROM " + db.t("town_pending_player_resets") + " WHERE player_uuid=?", UuidBytes.toBytes(playerUuid));
        db.update("UPDATE " + db.t("town_cleanup_job_members") + " SET reset_done=TRUE WHERE player_uuid=?", UuidBytes.toBytes(playerUuid));
    }

    public void failPendingPlayerReset(UUID playerUuid, String error) {
        if (playerUuid == null) return;
        db.update("UPDATE " + db.t("town_pending_player_resets") + " SET retry_count=retry_count+1, last_error=? WHERE player_uuid=?", error, UuidBytes.toBytes(playerUuid));
    }

    public int pendingPlayerResetCount() {
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("town_pending_player_resets"), rs -> rs.getInt("c")).orElse(0);
    }

    public int purgeExpiredCoopRequests(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMillis);
        return db.update("DELETE FROM " + db.t("town_coop_requests") + " WHERE created_at<?", cutoff);
    }

    public void replaceHeartFoundation(UUID townUuid, String world, List<FoundationBlock> blocks) {
        if (townUuid == null) return;
        byte[] townBytes = UuidBytes.toBytes(townUuid);
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("town_heart_foundation_blocks") + " WHERE town_uuid=?", townBytes);
            if (blocks != null) {
                for (FoundationBlock block : blocks) {
                    tx.update("INSERT INTO " + tx.t("town_heart_foundation_blocks") + " (town_uuid, world_name, x, y, z, previous_material) VALUES (?, ?, ?, ?, ?, ?)",
                            townBytes, world, block.x(), block.y(), block.z(), block.previousMaterial());
                }
            }
            return null;
        });
    }

    public List<FoundationBlock> loadHeartFoundation(UUID townUuid) {
        if (townUuid == null) return List.of();
        return db.query("SELECT x, y, z, previous_material FROM " + db.t("town_heart_foundation_blocks") + " WHERE town_uuid=?",
                rs -> new FoundationBlock(rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("previous_material")), UuidBytes.toBytes(townUuid));
    }

    public void deleteHeartFoundation(UUID townUuid) {
        if (townUuid != null) db.update("DELETE FROM " + db.t("town_heart_foundation_blocks") + " WHERE town_uuid=?", UuidBytes.toBytes(townUuid));
    }

    private String metaValue(Db source, long townId, String namespace, String key, String def) {
        return source.queryOne("SELECT v FROM " + source.t("town_meta") + " WHERE town_id=? AND ns=? AND k=?",
                rs -> rs.getString("v"), townId, namespace, key).orElse(def);
    }

    private static Integer parseNullableInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return null; }
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static UUID uuidOrNull(byte[] bytes) {
        return bytes == null ? null : UuidBytes.fromBytes(bytes);
    }

    private static String normalizeReason(String reason) {
        String value = reason == null || reason.isBlank() ? "UNKNOWN" : reason.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private void bestEffortUpdate(String sql, Object... args) {
        try { db.update(sql, args); } catch (RuntimeException ignored) { }
    }

    private Town mapTown(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Town(
                rs.getLong("id"), UuidBytes.fromBytes(rs.getBytes("uuid")), UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                rs.getString("name"), rs.getString("world_name"), rs.getInt("world_id"),
                new ChunkPos(rs.getInt("heart_cx"), rs.getInt("heart_cz")), rs.getInt("growth_points"),
                Instant.ofEpochMilli(rs.getLong("created_at")), TownStatus.valueOf(rs.getString("status")));
    }

    private record CleanupJobBase(UUID townUuid, long internalTownId, UUID ownerUuid, String townName, int worldId, String worldName,
                                  int heartCx, int heartCz, String heartWorld, Integer heartX, Integer heartY, Integer heartZ,
                                  String state, long createdAt, long updatedAt, int retryCount, String lastError, int boundScanCursor) {}

    public record CleanupPart(String subsystem, String state, int retries, String lastError, long updatedAt) {}
    public record CleanupJob(Town town, String state, String heartWorld, Integer heartX, Integer heartY, Integer heartZ,
                             List<ChunkPos> chunks, List<UUID> members, Map<String, CleanupPart> parts,
                             int retryCount, String lastError, long createdAt, long updatedAt, int boundScanCursor) {}
    public record CleanupJobSummary(UUID townUuid, String state, int retryCount, long updatedAt, String lastError) {}
    public record NamespaceRegistration(String namespace, String pluginName, long registeredAt, boolean active, long lastSeenAt, String pluginVersion, Long retiredAt) {}
    public record OrphanScanReport(Map<String, Integer> counts, List<String> warnings, String activePrefix) {}
    public record OrphanRepairReport(Map<String, Integer> affected, boolean applied) {}
    public record PendingPlayerReset(UUID playerUuid, UUID townUuid, String reason, long createdAt, int retryCount, String lastError) {}
    public record FoundationBlock(int x, int y, int z, String previousMaterial) {}

    public record InitialState(Map<String, Integer> worlds, List<Town> towns, List<ChunkRecord> chunks, List<MemberRecord> members,
                               List<MemberPermissionRecord> permissions) {
    }

    public record ChunkRecord(int worldId, int x, int z, long townId, int bucketX, int bucketZ) {
    }

    public record MemberRecord(UUID playerId, long townId, TownRole role) {
    }

    public record MemberPermissionRecord(UUID playerId, long townId, TownPermission permission, boolean allowed) {
    }

    public enum AcceptCoopDbResult {
        SUCCESS,
        NO_REQUEST,
        REQUESTER_HAS_TOWN,
        FULL,
        TOWN_INACTIVE,
        INVALID
    }

    public record CoopRequestRecord(UUID requesterId, long createdAt) {
    }

    public record CoopRequestDebugRecord(long townId, long createdAt) {
    }
}