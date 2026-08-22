package hex.minions.energy;

import hex.core.api.db.Db;
import hex.minions.util.UuidBytes;
import org.bukkit.Axis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Trwały zapis topologii kabli EU. To nie jest runtime engine: zwykły tick energetyczny
 * korzysta wyłącznie z cache w RAM, a DB jest używana tylko przy zmianie topologii lub starcie.
 */
public final class CableRepository {
    private final Db db;

    public CableRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("energy_cables") + " (" +
                "id VARCHAR(36) PRIMARY KEY," +
                "town_uuid BINARY(16) NULL," +
                "world VARCHAR(64) NOT NULL," +
                "x1 INT NOT NULL," +
                "y1 INT NOT NULL," +
                "z1 INT NOT NULL," +
                "x2 INT NOT NULL," +
                "y2 INT NOT NULL," +
                "z2 INT NOT NULL," +
                "axis VARCHAR(1) NOT NULL," +
                "cable_type VARCHAR(32) NOT NULL," +
                "length INT NOT NULL," +
                "network_id VARCHAR(36) NULL," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "KEY idx_world_pos1 (world, x1, y1, z1)," +
                "KEY idx_world_pos2 (world, x2, y2, z2)," +
                "KEY idx_network (network_id)," +
                "KEY idx_town_uuid (town_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        try { db.update("ALTER TABLE " + db.t("energy_cables") + " ADD COLUMN town_uuid BINARY(16) NULL AFTER id"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("energy_cables") + " ADD INDEX idx_town_uuid (town_uuid)"); } catch (RuntimeException ignored) { }
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("energy_cable_chunks") + " (" +
                "cable_id VARCHAR(36) NOT NULL," +
                "world VARCHAR(64) NOT NULL," +
                "chunk_x INT NOT NULL," +
                "chunk_z INT NOT NULL," +
                "PRIMARY KEY (cable_id, world, chunk_x, chunk_z)," +
                "KEY idx_chunk (world, chunk_x, chunk_z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        // Existing orphan chunk rows are reported/repaired explicitly by HexTowns. Avoid a
        // destructive startup migration; the FK is best-effort until the dataset is reconciled.
        try {
            String fkName = "fk_" + Integer.toUnsignedString(db.tablePrefix().hashCode(), 16) + "_energy_cable_chunks_parent";
            db.update("ALTER TABLE " + db.t("energy_cable_chunks") + " ADD CONSTRAINT " + fkName + " FOREIGN KEY (cable_id) REFERENCES " + db.t("energy_cables") + "(id) ON DELETE CASCADE");
        } catch (RuntimeException ignored) { }
    }

    public List<CableSegment> loadAll() {
        return db.query("SELECT id, town_uuid, world, x1, y1, z1, x2, y2, z2, axis, cable_type, length FROM " + db.t("energy_cables"), rs -> {
            try {
                UUID id = UUID.fromString(rs.getString("id"));
                byte[] townBytes = rs.getBytes("town_uuid");
                UUID townUuid = townBytes == null ? null : UuidBytes.fromBytes(townBytes);
                String world = rs.getString("world");
                BlockPos start = new BlockPos(world, rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"));
                BlockPos end = new BlockPos(world, rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2"));
                Axis axis = Axis.valueOf(rs.getString("axis"));
                CableType type = CableType.valueOf(rs.getString("cable_type"));
                int length = Math.max(1, rs.getInt("length"));
                return new CableSegment(id, townUuid, world, start, end, axis, type, length);
            } catch (Throwable ignored) {
                return null;
            }
        }).stream().filter(java.util.Objects::nonNull).toList();
    }

    public void insertCable(CableSegment segment) {
        if (segment == null) return;
        long now = System.currentTimeMillis();
        byte[] townBytes = segment.townUuid() == null ? null : UuidBytes.toBytes(segment.townUuid());
        int changed;
        if (townBytes == null) {
            // Kept only for compatibility with historical callers. New placements always have an owner.
            changed = db.update("INSERT INTO " + db.t("energy_cables") + " (" +
                            "id, town_uuid, world, x1, y1, z1, x2, y2, z2, axis, cable_type, length, network_id, created_at, updated_at" +
                            ") VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE world=VALUES(world), x1=VALUES(x1), y1=VALUES(y1), z1=VALUES(z1), " +
                            "x2=VALUES(x2), y2=VALUES(y2), z2=VALUES(z2), axis=VALUES(axis), cable_type=VALUES(cable_type), " +
                            "length=VALUES(length), updated_at=VALUES(updated_at)",
                    segment.id().toString(), segment.world(), segment.start().x(), segment.start().y(), segment.start().z(),
                    segment.end().x(), segment.end().y(), segment.end().z(), segment.axis().name(), segment.type().name(),
                    segment.length(), now, now);
        } else {
            // Final DB-side lifecycle guard closes the small race between the queue fence check and SQL execution.
            changed = db.update("INSERT INTO " + db.t("energy_cables") + " (" +
                            "id, town_uuid, world, x1, y1, z1, x2, y2, z2, axis, cable_type, length, network_id, created_at, updated_at" +
                            ") SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ? " +
                            "WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE uuid=? AND status='ACTIVE') " +
                            "ON DUPLICATE KEY UPDATE town_uuid=VALUES(town_uuid), world=VALUES(world), x1=VALUES(x1), y1=VALUES(y1), z1=VALUES(z1), " +
                            "x2=VALUES(x2), y2=VALUES(y2), z2=VALUES(z2), axis=VALUES(axis), cable_type=VALUES(cable_type), " +
                            "length=VALUES(length), updated_at=VALUES(updated_at)",
                    segment.id().toString(), townBytes, segment.world(), segment.start().x(), segment.start().y(), segment.start().z(),
                    segment.end().x(), segment.end().y(), segment.end().z(), segment.axis().name(), segment.type().name(),
                    segment.length(), now, now, townBytes);
        }
        // Never create a child chunk index when the ACTIVE-town guard rejected the parent write.
        if (changed > 0) replaceChunkIndex(segment);
    }

    public void deleteCable(UUID cableId) {
        if (cableId == null) return;
        db.update("DELETE FROM " + db.t("energy_cable_chunks") + " WHERE cable_id=?", cableId.toString());
        db.update("DELETE FROM " + db.t("energy_cables") + " WHERE id=?", cableId.toString());
    }

    public List<CableSegment> loadByTown(UUID townUuid) {
        if (townUuid == null) return List.of();
        return loadAll().stream().filter(segment -> townUuid.equals(segment.townUuid())).toList();
    }

    public void assignTown(UUID cableId, UUID townUuid) {
        if (cableId == null) return;
        db.update("UPDATE " + db.t("energy_cables") + " SET town_uuid=?, updated_at=? WHERE id=?",
                townUuid == null ? null : UuidBytes.toBytes(townUuid), System.currentTimeMillis(), cableId.toString());
    }

    public int deleteByTown(UUID townUuid) {
        if (townUuid == null) return 0;
        byte[] townBytes = UuidBytes.toBytes(townUuid);
        List<String> ids = db.query("SELECT id FROM " + db.t("energy_cables") + " WHERE town_uuid=?", rs -> rs.getString("id"), townBytes);
        for (String id : ids) db.update("DELETE FROM " + db.t("energy_cable_chunks") + " WHERE cable_id=?", id);
        return db.update("DELETE FROM " + db.t("energy_cables") + " WHERE town_uuid=?", townBytes);
    }

    /** Legacy fallback: only null-owner cables referenced by the exact destroy chunk snapshot are candidates. */
    public List<UUID> legacyCableIdsInChunks(String world, java.util.Set<Long> chunkKeys) {
        if (world == null || chunkKeys == null || chunkKeys.isEmpty()) return List.of();
        java.util.LinkedHashSet<UUID> result = new java.util.LinkedHashSet<>();
        for (long key : chunkKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            List<String> ids = db.query("SELECT c.id FROM " + db.t("energy_cables") + " c JOIN " + db.t("energy_cable_chunks") + " cc ON cc.cable_id=c.id WHERE c.town_uuid IS NULL AND cc.world=? AND cc.chunk_x=? AND cc.chunk_z=?",
                    rs -> rs.getString("id"), world, cx, cz);
            for (String id : ids) {
                try { result.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return List.copyOf(result);
    }

    public int countByTown(UUID townUuid) {
        if (townUuid == null) return 0;
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("energy_cables") + " WHERE town_uuid=?",
                rs -> rs.getInt("c"), UuidBytes.toBytes(townUuid)).orElse(0);
    }

    public int countOrphanChunkRows() {
        return db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("energy_cable_chunks") + " cc LEFT JOIN " +
                        db.t("energy_cables") + " c ON c.id=cc.cable_id WHERE c.id IS NULL",
                rs -> rs.getInt("c")).orElse(0);
    }

    public void verifyTownPurged(UUID townUuid, String worldName, java.util.Set<Long> snapshotChunks) {
        int remaining = countByTown(townUuid);
        if (worldName != null && snapshotChunks != null && !snapshotChunks.isEmpty()) {
            for (CableSegment segment : loadAll()) {
                if (segment.townUuid() != null || !worldName.equals(segment.world())) continue;
                java.util.Set<Long> touched = segment.chunkKeys();
                if (!touched.isEmpty() && snapshotChunks.containsAll(touched)) remaining++;
            }
        }
        if (remaining != 0) {
            throw new IllegalStateException("Cable purge verification failed town=" + townUuid + " remaining=" + remaining);
        }
    }

    public void updateCableNetwork(UUID cableId, UUID networkId) {
        if (cableId == null) return;
        db.update("UPDATE " + db.t("energy_cables") + " SET network_id=?, updated_at=? WHERE id=?",
                networkId == null ? null : networkId.toString(), System.currentTimeMillis(), cableId.toString());
    }

    private void replaceChunkIndex(CableSegment segment) {
        db.update("DELETE FROM " + db.t("energy_cable_chunks") + " WHERE cable_id=?", segment.id().toString());
        List<Object[]> rows = new ArrayList<>();
        for (long key : segment.chunkKeys()) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            rows.add(new Object[]{segment.id().toString(), segment.world(), chunkX, chunkZ});
        }
        if (!rows.isEmpty()) {
            db.batch("INSERT INTO " + db.t("energy_cable_chunks") + " (cable_id, world, chunk_x, chunk_z) VALUES (?, ?, ?, ?)", rows);
        }
    }
}
