package hex.minions.database;

import hex.core.api.db.Db;
import hex.minions.model.MinionInstance;
import hex.minions.model.MinionLocation;
import hex.minions.model.MinionState;
import hex.minions.util.UuidBytes;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MinionRepository {
    private final Db db;

    public MinionRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minions") + " (" +
                "id BINARY(16) NOT NULL," +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "town_uuid BINARY(16) NOT NULL," +
                "owner_uuid BINARY(16) NOT NULL," +
                "type_id VARCHAR(64) NOT NULL," +
                "tier SMALLINT UNSIGNED NOT NULL DEFAULT 1," +
                "world VARCHAR(64) NOT NULL," +
                "x INT NOT NULL," +
                "y SMALLINT NOT NULL," +
                "z INT NOT NULL," +
                "yaw FLOAT NOT NULL DEFAULT 0," +
                "state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'," +
                "appearance_id VARCHAR(64) NULL," +
                "storage_limit INT NOT NULL DEFAULT 0," +
                "storage_used INT NOT NULL DEFAULT 0," +
                "placed_at BIGINT NOT NULL," +
                "last_action_at BIGINT NOT NULL," +
                "next_action_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (id)," +
                "KEY idx_town (town_id)," +
                "KEY idx_town_type (town_id, type_id)," +
                "KEY idx_world_chunk (world, x, z)," +
                "KEY idx_next_action (state, next_action_at)," +
                "UNIQUE KEY uq_location (world, x, y, z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minion_storage") + " (" +
                "minion_id BINARY(16) NOT NULL," +
                "resource_id VARCHAR(64) NOT NULL," +
                "amount BIGINT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (minion_id, resource_id)," +
                "KEY idx_resource (resource_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minion_upgrades") + " (" +
                "minion_id BINARY(16) NOT NULL," +
                "slot VARCHAR(32) NOT NULL," +
                "upgrade_id VARCHAR(64) NOT NULL," +
                "level SMALLINT UNSIGNED NOT NULL DEFAULT 1," +
                "expires_at BIGINT NULL," +
                "data_json TEXT NULL," +
                "installed_at BIGINT NOT NULL," +
                "PRIMARY KEY (minion_id, slot)," +
                "KEY idx_upgrade (upgrade_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("town_minion_stats") + " (" +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "type_id VARCHAR(64) NOT NULL," +
                "placed_count INT NOT NULL DEFAULT 0," +
                "max_tier SMALLINT UNSIGNED NOT NULL DEFAULT 0," +
                "total_actions BIGINT NOT NULL DEFAULT 0," +
                "total_output BIGINT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, type_id)," +
                "KEY idx_town (town_id)," +
                "KEY idx_town_type_tier (town_id, type_id, max_tier)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        try { db.update("ALTER TABLE " + db.t("town_minion_stats") + " ADD COLUMN max_tier SMALLINT UNSIGNED NOT NULL DEFAULT 0 AFTER placed_count"); } catch (Exception ignored) {}

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minion_audit_log") + " (" +
                "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT," +
                "minion_id BINARY(16) NULL," +
                "town_id BIGINT UNSIGNED NOT NULL," +
                "actor_uuid BINARY(16) NULL," +
                "action VARCHAR(48) NOT NULL," +
                "data_json TEXT NULL," +
                "created_at BIGINT NOT NULL," +
                "PRIMARY KEY (id)," +
                "KEY idx_town_created (town_id, created_at)," +
                "KEY idx_minion_created (minion_id, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public List<MinionInstance> loadMinions() {
        return db.query("SELECT id, town_id, town_uuid, owner_uuid, type_id, tier, world, x, y, z, yaw, state, appearance_id, " +
                        "storage_limit, storage_used, placed_at, last_action_at, next_action_at FROM " + db.t("minions"),
                rs -> new MinionInstance(
                        UuidBytes.fromBytes(rs.getBytes("id")),
                        rs.getLong("town_id"),
                        UuidBytes.fromBytes(rs.getBytes("town_uuid")),
                        UuidBytes.fromBytes(rs.getBytes("owner_uuid")),
                        rs.getString("type_id"),
                        rs.getInt("tier"),
                        new MinionLocation(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getFloat("yaw")),
                        parseState(rs.getString("state")),
                        rs.getLong("placed_at"),
                        rs.getLong("last_action_at"),
                        rs.getLong("next_action_at"),
                        rs.getInt("storage_used"),
                        rs.getInt("storage_limit"),
                        rs.getString("appearance_id")
                ));
    }

    public Map<String, ItemStack> loadAddonItems(UUID minionId) {
        Map<String, ItemStack> result = new LinkedHashMap<>();
        db.query("SELECT slot, data_json FROM " + db.t("minion_upgrades") + " WHERE minion_id=? AND upgrade_id='MENU_ITEM'", rs -> {
            String slot = rs.getString("slot");
            ItemStack item = deserializeItem(rs.getString("data_json"));
            if (slot != null && item != null && !item.getType().isAir()) result.put(slot, item);
            return null;
        }, UuidBytes.toBytes(minionId));
        return result;
    }

    public Map<String, Long> loadStorage(UUID minionId) {
        Map<String, Long> result = new LinkedHashMap<>();
        db.query("SELECT resource_id, amount FROM " + db.t("minion_storage") + " WHERE minion_id=?", rs -> {
            result.put(rs.getString("resource_id"), rs.getLong("amount"));
            return null;
        }, UuidBytes.toBytes(minionId));
        return result;
    }

    public void insertMinion(MinionInstance minion) {
        db.update("INSERT INTO " + db.t("minions") + " (id, town_id, town_uuid, owner_uuid, type_id, tier, world, x, y, z, yaw, state, appearance_id, storage_limit, storage_used, placed_at, last_action_at, next_action_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UuidBytes.toBytes(minion.id()), minion.townInternalId(), UuidBytes.toBytes(minion.townUuid()), UuidBytes.toBytes(minion.ownerUuid()),
                minion.typeId(), minion.tier(), minion.location().world(), minion.location().x(), minion.location().y(), minion.location().z(), minion.location().yaw(),
                minion.state().name(), minion.appearanceId(), minion.storageLimit(), minion.storageUsed(), minion.placedAt(), minion.lastActionAt(), minion.nextActionAt(), System.currentTimeMillis());
    }

    public boolean moveMinion(UUID minionId, MinionLocation target) {
        return db.update("UPDATE " + db.t("minions") + " SET world=?, x=?, y=?, z=?, yaw=?, updated_at=? WHERE id=? AND state='ACTIVE'",
                target.world(), target.x(), target.y(), target.z(), target.yaw(), System.currentTimeMillis(), UuidBytes.toBytes(minionId)) > 0;
    }

    public void updateRuntime(MinionInstance minion) {
        db.update("UPDATE " + db.t("minions") + " SET tier=?, state=?, storage_limit=?, storage_used=?, last_action_at=?, next_action_at=?, updated_at=? WHERE id=?",
                minion.tier(), minion.state().name(), minion.storageLimit(), minion.storageUsed(), minion.lastActionAt(), minion.nextActionAt(), System.currentTimeMillis(), UuidBytes.toBytes(minion.id()));
    }
    public void updateRuntimeBatch(java.util.Collection<MinionInstance> minions) {
        if (minions == null || minions.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>();
        for (MinionInstance minion : minions) {
            if (minion == null) continue;
            batch.add(new Object[]{minion.tier(), minion.state().name(), minion.storageLimit(), minion.storageUsed(), minion.lastActionAt(), minion.nextActionAt(), now, UuidBytes.toBytes(minion.id())});
        }
        if (!batch.isEmpty()) {
            db.batch("UPDATE " + db.t("minions") + " SET tier=?, state=?, storage_limit=?, storage_used=?, last_action_at=?, next_action_at=?, updated_at=? WHERE id=?", batch);
        }
    }


    public void upsertStorage(UUID minionId, Map<String, Long> storage) {
        if (storage.isEmpty()) {
            db.update("DELETE FROM " + db.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
            return;
        }
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
            List<Object[]> batch = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : storage.entrySet()) {
                if (entry.getValue() > 0) batch.add(new Object[]{UuidBytes.toBytes(minionId), entry.getKey(), entry.getValue(), now});
            }
            if (!batch.isEmpty()) {
                tx.batch("INSERT INTO " + tx.t("minion_storage") + " (minion_id, resource_id, amount, updated_at) VALUES (?, ?, ?, ?)", batch);
            }
            return null;
        });
    }

    public void upsertStorageBatch(java.util.Collection<MinionInstance> minions) {
        if (minions == null || minions.isEmpty()) return;
        db.tx(tx -> {
            List<Object[]> deletes = new ArrayList<>();
            List<Object[]> inserts = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (MinionInstance minion : minions) {
                if (minion == null) continue;
                byte[] id = UuidBytes.toBytes(minion.id());
                deletes.add(new Object[]{id});
                for (Map.Entry<String, Long> entry : minion.storage().entrySet()) {
                    if (entry.getValue() > 0) inserts.add(new Object[]{id, entry.getKey(), entry.getValue(), now});
                }
            }
            if (!deletes.isEmpty()) tx.batch("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", deletes);
            if (!inserts.isEmpty()) tx.batch("INSERT INTO " + tx.t("minion_storage") + " (minion_id, resource_id, amount, updated_at) VALUES (?, ?, ?, ?)", inserts);
            return null;
        });
    }

    public void upsertAddonItems(UUID minionId, Map<String, ItemStack> addonItems) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=? AND upgrade_id='MENU_ITEM'", UuidBytes.toBytes(minionId));
            List<Object[]> batch = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ItemStack> entry : addonItems.entrySet()) {
                ItemStack item = entry.getValue();
                if (entry.getKey() == null || entry.getKey().isBlank() || item == null || item.getType().isAir()) continue;
                batch.add(new Object[]{UuidBytes.toBytes(minionId), entry.getKey(), "MENU_ITEM", 1, serializeItem(item), now});
            }
            if (!batch.isEmpty()) {
                tx.batch("INSERT INTO " + tx.t("minion_upgrades") + " (minion_id, slot, upgrade_id, level, data_json, installed_at) VALUES (?, ?, ?, ?, ?, ?)", batch);
            }
            return null;
        });
    }

    public void upsertAddonItemsBatch(java.util.Collection<MinionInstance> minions) {
        if (minions == null || minions.isEmpty()) return;
        db.tx(tx -> {
            List<Object[]> deletes = new ArrayList<>();
            List<Object[]> inserts = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (MinionInstance minion : minions) {
                if (minion == null) continue;
                byte[] id = UuidBytes.toBytes(minion.id());
                deletes.add(new Object[]{id});
                for (Map.Entry<String, ItemStack> entry : minion.addonItems().entrySet()) {
                    ItemStack item = entry.getValue();
                    if (entry.getKey() == null || entry.getKey().isBlank() || item == null || item.getType().isAir()) continue;
                    inserts.add(new Object[]{id, entry.getKey(), "MENU_ITEM", 1, serializeItem(item), now});
                }
            }
            if (!deletes.isEmpty()) tx.batch("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=? AND upgrade_id='MENU_ITEM'", deletes);
            if (!inserts.isEmpty()) tx.batch("INSERT INTO " + tx.t("minion_upgrades") + " (minion_id, slot, upgrade_id, level, data_json, installed_at) VALUES (?, ?, ?, ?, ?, ?)", inserts);
            return null;
        });
    }

    public void recordTownMinionTier(long townId, String typeId, int tier) {
        db.update("INSERT INTO " + db.t("town_minion_stats") + " (town_id, type_id, placed_count, max_tier, updated_at) VALUES (?, ?, 1, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE placed_count=placed_count+1, max_tier=GREATEST(max_tier, VALUES(max_tier)), updated_at=VALUES(updated_at)",
                townId, typeId, tier, System.currentTimeMillis());
    }

    public void updateTownMinionMaxTier(long townId, String typeId, int tier) {
        db.update("INSERT INTO " + db.t("town_minion_stats") + " (town_id, type_id, placed_count, max_tier, updated_at) VALUES (?, ?, 0, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE max_tier=GREATEST(max_tier, VALUES(max_tier)), updated_at=VALUES(updated_at)",
                townId, typeId, tier, System.currentTimeMillis());
    }

    public int townMinionMaxTier(long townId, String typeId) {
        return db.queryOne("SELECT max_tier FROM " + db.t("town_minion_stats") + " WHERE town_id=? AND type_id=?", rs -> rs.getInt("max_tier"), townId, typeId).orElse(0);
    }

    public void deleteMinion(UUID minionId) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
            tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
            tx.update("DELETE FROM " + tx.t("minions") + " WHERE id=?", UuidBytes.toBytes(minionId));
            return null;
        });
    }

    public void deleteByTownId(long townId) {
        db.tx(tx -> {
            List<UUID> ids = tx.query("SELECT id FROM " + tx.t("minions") + " WHERE town_id=?", rs -> UuidBytes.fromBytes(rs.getBytes("id")), townId);
            for (UUID id : ids) {
                tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(id));
                tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=?", UuidBytes.toBytes(id));
            }
            tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_minion_stats") + " WHERE town_id=?", townId);
            return null;
        });
    }

    public Optional<Long> findInternalTownId(UUID townUuid) {
        return db.queryOne("SELECT town_id FROM " + db.t("minions") + " WHERE town_uuid=? LIMIT 1", rs -> rs.getLong("town_id"), UuidBytes.toBytes(townUuid));
    }

    public void audit(UUID minionId, long townId, UUID actor, String action, String data) {
        db.update("INSERT INTO " + db.t("minion_audit_log") + " (minion_id, town_id, actor_uuid, action, data_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                minionId == null ? null : UuidBytes.toBytes(minionId), townId, actor == null ? null : UuidBytes.toBytes(actor), action, data, System.currentTimeMillis());
    }

    private String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream data = new BukkitObjectOutputStream(output)) {
                data.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            return "";
        }
    }

    private ItemStack deserializeItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            try (BukkitObjectInputStream data = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                Object object = data.readObject();
                return object instanceof ItemStack item ? item : null;
            }
        } catch (Exception exception) {
            return null;
        }
    }

    private MinionState parseState(String raw) {
        try {
            return MinionState.valueOf(raw);
        } catch (Exception ignored) {
            return MinionState.DISABLED;
        }
    }
}

