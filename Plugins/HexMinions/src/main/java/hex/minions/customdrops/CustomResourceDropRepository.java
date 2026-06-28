package hex.minions.customdrops;

import hex.core.api.db.Db;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Trwały zapis custom resource dropów. Zgodnie ze specyfikacją nie zapisujemy osobnych
 * rekordów dla kamieni ani rud: jeden rekord zawiera skompresowane dane jednego chunka.
 */
public final class CustomResourceDropRepository {
    private static final int DATA_VERSION = 1;
    private final Db db;

    public CustomResourceDropRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("resource_chunk_data") + " (" +
                "world VARCHAR(64) NOT NULL," +
                "chunk_x INT NOT NULL," +
                "chunk_z INT NOT NULL," +
                "data_blob LONGBLOB NOT NULL," +
                "data_version INT NOT NULL DEFAULT 1," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (world, chunk_x, chunk_z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public Map<ChunkKey, LoadedChunkData> loadBatch(List<ChunkKey> keys) {
        Map<ChunkKey, LoadedChunkData> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) return result;
        Map<String, List<ChunkKey>> byWorld = new HashMap<>();
        for (ChunkKey key : keys) {
            if (key == null) continue;
            byWorld.computeIfAbsent(key.world(), ignored -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<String, List<ChunkKey>> entry : byWorld.entrySet()) {
            List<ChunkKey> worldKeys = entry.getValue();
            if (worldKeys.isEmpty()) continue;
            StringBuilder sql = new StringBuilder("SELECT world, chunk_x, chunk_z, data_blob FROM ")
                    .append(db.t("resource_chunk_data"))
                    .append(" WHERE world=? AND (");
            List<Object> params = new ArrayList<>();
            params.add(entry.getKey());
            for (int i = 0; i < worldKeys.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("(chunk_x=? AND chunk_z=?)");
                params.add(worldKeys.get(i).chunkX());
                params.add(worldKeys.get(i).chunkZ());
            }
            sql.append(")");
            db.query(sql.toString(), rs -> {
                ChunkKey key = new ChunkKey(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"));
                try {
                    result.put(key, deserialize(key, rs.getBytes("data_blob")));
                } catch (Exception ignored) {
                    result.put(key, new LoadedChunkData(Set.of(), Set.of()));
                }
                return null;
            }, params.toArray());
        }
        return result;
    }

    public void saveBatch(List<ResourceChunkData.Snapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        List<Object[]> upserts = new ArrayList<>();
        List<Object[]> deletes = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ResourceChunkData.Snapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.key() == null) continue;
            ChunkKey key = snapshot.key();
            if (snapshot.empty()) {
                deletes.add(new Object[]{key.world(), key.chunkX(), key.chunkZ()});
                continue;
            }
            try {
                upserts.add(new Object[]{key.world(), key.chunkX(), key.chunkZ(), serialize(key, snapshot), DATA_VERSION, now});
            } catch (Exception ignored) {
                // Pomijamy tylko uszkodzony snapshot; następny autosave zapisze ponowną wersję,
                // jeśli chunk zostanie oznaczony jako dirty.
            }
        }
        if (!upserts.isEmpty()) {
            db.batch("INSERT INTO " + db.t("resource_chunk_data") +
                    " (world, chunk_x, chunk_z, data_blob, data_version, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE data_blob=VALUES(data_blob), data_version=VALUES(data_version), updated_at=VALUES(updated_at)", upserts);
        }
        if (!deletes.isEmpty()) {
            db.batch("DELETE FROM " + db.t("resource_chunk_data") + " WHERE world=? AND chunk_x=? AND chunk_z=?", deletes);
        }
    }

    private static byte[] serialize(ChunkKey key, ResourceChunkData.Snapshot snapshot) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes); DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeInt(DATA_VERSION);
            out.writeInt(snapshot.ghostCopper().size());
            for (BlockPos pos : snapshot.ghostCopper()) out.writeInt(pos.packedLocal());
            out.writeInt(snapshot.playerPlaced().size());
            for (BlockPos pos : snapshot.playerPlaced()) out.writeInt(pos.packedLocal());
        }
        return bytes.toByteArray();
    }

    private static LoadedChunkData deserialize(ChunkKey key, byte[] blob) throws Exception {
        if (blob == null || blob.length == 0) return new LoadedChunkData(Set.of(), Set.of());
        Set<BlockPos> ghosts = new HashSet<>();
        Set<BlockPos> placed = new HashSet<>();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(blob)); DataInputStream in = new DataInputStream(gzip)) {
            int version = in.readInt();
            if (version != DATA_VERSION) return new LoadedChunkData(Set.of(), Set.of());
            int ghostCount = Math.max(0, in.readInt());
            for (int i = 0; i < ghostCount; i++) ghosts.add(BlockPos.unpack(key, in.readInt()));
            int placedCount = Math.max(0, in.readInt());
            for (int i = 0; i < placedCount; i++) placed.add(BlockPos.unpack(key, in.readInt()));
        }
        ghosts.removeAll(placed);
        return new LoadedChunkData(Set.copyOf(ghosts), Set.copyOf(placed));
    }

    public record LoadedChunkData(Set<BlockPos> ghostCopper, Set<BlockPos> playerPlaced) {
    }
}
