package hex.minions.energy;

import hex.core.api.db.Db;
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
                "KEY idx_network (network_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("energy_cable_chunks") + " (" +
                "cable_id VARCHAR(36) NOT NULL," +
                "world VARCHAR(64) NOT NULL," +
                "chunk_x INT NOT NULL," +
                "chunk_z INT NOT NULL," +
                "PRIMARY KEY (cable_id, world, chunk_x, chunk_z)," +
                "KEY idx_chunk (world, chunk_x, chunk_z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public List<CableSegment> loadAll() {
        return db.query("SELECT id, world, x1, y1, z1, x2, y2, z2, axis, cable_type, length FROM " + db.t("energy_cables"), rs -> {
            try {
                UUID id = UUID.fromString(rs.getString("id"));
                String world = rs.getString("world");
                BlockPos start = new BlockPos(world, rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"));
                BlockPos end = new BlockPos(world, rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2"));
                Axis axis = Axis.valueOf(rs.getString("axis"));
                CableType type = CableType.valueOf(rs.getString("cable_type"));
                int length = Math.max(1, rs.getInt("length"));
                return new CableSegment(id, world, start, end, axis, type, length);
            } catch (Throwable ignored) {
                return null;
            }
        }).stream().filter(java.util.Objects::nonNull).toList();
    }

    public void insertCable(CableSegment segment) {
        if (segment == null) return;
        long now = System.currentTimeMillis();
        db.update("INSERT INTO " + db.t("energy_cables") + " (" +
                        "id, world, x1, y1, z1, x2, y2, z2, axis, cable_type, length, network_id, created_at, updated_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE world=VALUES(world), x1=VALUES(x1), y1=VALUES(y1), z1=VALUES(z1), " +
                        "x2=VALUES(x2), y2=VALUES(y2), z2=VALUES(z2), axis=VALUES(axis), cable_type=VALUES(cable_type), " +
                        "length=VALUES(length), updated_at=VALUES(updated_at)",
                segment.id().toString(), segment.world(), segment.start().x(), segment.start().y(), segment.start().z(),
                segment.end().x(), segment.end().y(), segment.end().z(), segment.axis().name(), segment.type().name(),
                segment.length(), now, now);
        replaceChunkIndex(segment);
    }

    public void deleteCable(UUID cableId) {
        if (cableId == null) return;
        db.update("DELETE FROM " + db.t("energy_cable_chunks") + " WHERE cable_id=?", cableId.toString());
        db.update("DELETE FROM " + db.t("energy_cables") + " WHERE id=?", cableId.toString());
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
