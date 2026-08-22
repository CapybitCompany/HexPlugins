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
import java.util.Set;
import java.util.UUID;

public final class MinionRepository {
    private static final Set<String> AUDITED_ACTIONS = Set.of("PLACE", "MOVE", "UPGRADE", "PICKUP", "REMOVE");

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

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minion_drop_progress") + " (" +
                "minion_id BINARY(16) NOT NULL," +
                "resource_id VARCHAR(64) NOT NULL," +
                "progress BIGINT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (minion_id, resource_id)" +
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

        // Do not silently delete historical rows during startup. FK creation is best-effort and
        // will succeed once the explicit HexTowns orphan repair has reconciled old child rows.
        String fkPrefix = "fk_" + Integer.toUnsignedString(db.tablePrefix().hashCode(), 16) + "_";
        try { db.update("ALTER TABLE " + db.t("minion_storage") + " ADD CONSTRAINT " + fkPrefix + "minion_storage_parent FOREIGN KEY (minion_id) REFERENCES " + db.t("minions") + "(id) ON DELETE CASCADE"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("minion_drop_progress") + " ADD CONSTRAINT " + fkPrefix + "minion_drop_parent FOREIGN KEY (minion_id) REFERENCES " + db.t("minions") + "(id) ON DELETE CASCADE"); } catch (RuntimeException ignored) { }
        try { db.update("ALTER TABLE " + db.t("minion_upgrades") + " ADD CONSTRAINT " + fkPrefix + "minion_upgrade_parent FOREIGN KEY (minion_id) REFERENCES " + db.t("minions") + "(id) ON DELETE CASCADE"); } catch (RuntimeException ignored) { }
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


    public Map<String, Long> loadDeterministicDropProgress(UUID minionId) {
        Map<String, Long> result = new LinkedHashMap<>();
        db.query("SELECT resource_id, progress FROM " + db.t("minion_drop_progress") + " WHERE minion_id=?", rs -> {
            long progress = rs.getLong("progress");
            if (progress > 0L) result.put(rs.getString("resource_id"), progress);
            return null;
        }, UuidBytes.toBytes(minionId));
        return result;
    }

    public void insertMinion(MinionInstance minion) {
        if (minion == null) return;
        byte[] townUuid = UuidBytes.toBytes(minion.townUuid());
        int inserted = db.update("INSERT INTO " + db.t("minions") + " (id, town_id, town_uuid, owner_uuid, type_id, tier, world, x, y, z, yaw, state, appearance_id, storage_limit, storage_used, placed_at, last_action_at, next_action_at, updated_at) " +
                        "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? " +
                        "WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE id=? AND uuid=? AND status='ACTIVE')",
                UuidBytes.toBytes(minion.id()), minion.townInternalId(), townUuid, UuidBytes.toBytes(minion.ownerUuid()),
                minion.typeId(), minion.tier(), minion.location().world(), minion.location().x(), minion.location().y(), minion.location().z(), minion.location().yaw(),
                minion.state().name(), minion.appearanceId(), minion.storageLimit(), minion.storageUsed(), minion.placedAt(), minion.lastActionAt(), minion.nextActionAt(), System.currentTimeMillis(),
                minion.townInternalId(), townUuid);
        if (inserted != 1) throw new IllegalStateException("Town is no longer ACTIVE for minion placement: " + minion.townUuid());
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
                List<Object[]> guarded = new ArrayList<>();
                for (Object[] row : batch) guarded.add(new Object[]{row[0], row[1], row[2], row[3], row[0]});
                tx.batch("INSERT INTO " + tx.t("minion_storage") + " (minion_id, resource_id, amount, updated_at) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("minions") + " WHERE id=?)", guarded);
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
            if (!inserts.isEmpty()) {
                List<Object[]> guarded = new ArrayList<>();
                for (Object[] row : inserts) guarded.add(new Object[]{row[0], row[1], row[2], row[3], row[0]});
                tx.batch("INSERT INTO " + tx.t("minion_storage") + " (minion_id, resource_id, amount, updated_at) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("minions") + " WHERE id=?)", guarded);
            }
            return null;
        });
    }


    public void upsertDeterministicDropProgressBatch(java.util.Collection<MinionInstance> minions) {
        if (minions == null || minions.isEmpty()) return;
        db.tx(tx -> {
            List<Object[]> deletes = new ArrayList<>();
            List<Object[]> inserts = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (MinionInstance minion : minions) {
                if (minion == null) continue;
                byte[] id = UuidBytes.toBytes(minion.id());
                deletes.add(new Object[]{id});
                for (Map.Entry<String, Long> entry : minion.deterministicDropProgress().entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && entry.getValue() > 0L) {
                        inserts.add(new Object[]{id, entry.getKey(), entry.getValue(), now});
                    }
                }
            }
            if (!deletes.isEmpty()) tx.batch("DELETE FROM " + tx.t("minion_drop_progress") + " WHERE minion_id=?", deletes);
            if (!inserts.isEmpty()) {
                List<Object[]> guarded = new ArrayList<>();
                for (Object[] row : inserts) guarded.add(new Object[]{row[0], row[1], row[2], row[3], row[0]});
                tx.batch("INSERT INTO " + tx.t("minion_drop_progress") + " (minion_id, resource_id, progress, updated_at) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("minions") + " WHERE id=?)", guarded);
            }
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
                List<Object[]> guarded = new ArrayList<>();
                for (Object[] row : batch) guarded.add(new Object[]{row[0], row[1], row[2], row[3], row[4], row[5], row[0]});
                tx.batch("INSERT INTO " + tx.t("minion_upgrades") + " (minion_id, slot, upgrade_id, level, data_json, installed_at) SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("minions") + " WHERE id=?)", guarded);
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
            if (!inserts.isEmpty()) {
                List<Object[]> guarded = new ArrayList<>();
                for (Object[] row : inserts) guarded.add(new Object[]{row[0], row[1], row[2], row[3], row[4], row[5], row[0]});
                tx.batch("INSERT INTO " + tx.t("minion_upgrades") + " (minion_id, slot, upgrade_id, level, data_json, installed_at) SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + tx.t("minions") + " WHERE id=?)", guarded);
            }
            return null;
        });
    }

    public void recordTownMinionTier(long townId, String typeId, int tier) {
        db.update("INSERT INTO " + db.t("town_minion_stats") + " (town_id, type_id, placed_count, max_tier, updated_at) " +
                        "SELECT ?, ?, 1, ?, ? WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE id=? AND status='ACTIVE') " +
                        "ON DUPLICATE KEY UPDATE placed_count=placed_count+1, max_tier=GREATEST(max_tier, VALUES(max_tier)), updated_at=VALUES(updated_at)",
                townId, typeId, tier, System.currentTimeMillis(), townId);
    }

    public void updateTownMinionMaxTier(long townId, String typeId, int tier) {
        db.update("INSERT INTO " + db.t("town_minion_stats") + " (town_id, type_id, placed_count, max_tier, updated_at) " +
                        "SELECT ?, ?, 0, ?, ? WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE id=? AND status='ACTIVE') " +
                        "ON DUPLICATE KEY UPDATE max_tier=GREATEST(max_tier, VALUES(max_tier)), updated_at=VALUES(updated_at)",
                townId, typeId, tier, System.currentTimeMillis(), townId);
    }

    public int townMinionMaxTier(long townId, String typeId) {
        return db.queryOne("SELECT max_tier FROM " + db.t("town_minion_stats") + " WHERE town_id=? AND type_id=?", rs -> rs.getInt("max_tier"), townId, typeId).orElse(0);
    }

    public int countKnownTownMinionTypes(long townId) {
        return db.queryOne("SELECT COUNT(*) AS count FROM " + db.t("town_minion_stats") + " WHERE town_id=? AND placed_count>0", rs -> rs.getInt("count"), townId).orElse(0);
    }

    public void deleteMinion(UUID minionId) {
        db.tx(tx -> {
            tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
            tx.update("DELETE FROM " + tx.t("minion_drop_progress") + " WHERE minion_id=?", UuidBytes.toBytes(minionId));
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
                tx.update("DELETE FROM " + tx.t("minion_drop_progress") + " WHERE minion_id=?", UuidBytes.toBytes(id));
                tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=?", UuidBytes.toBytes(id));
            }
            tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("town_minion_stats") + " WHERE town_id=?", townId);
            tx.update("DELETE FROM " + tx.t("minion_audit_log") + " WHERE town_id=?", townId);
            return null;
        });
    }

    public void deleteByTownUuid(UUID townUuid) {
        if (townUuid == null) return;
        db.tx(tx -> {
            List<UUID> ids = tx.query("SELECT id FROM " + tx.t("minions") + " WHERE town_uuid=?", rs -> UuidBytes.fromBytes(rs.getBytes("id")), UuidBytes.toBytes(townUuid));
            for (UUID id : ids) {
                tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", UuidBytes.toBytes(id));
                tx.update("DELETE FROM " + tx.t("minion_drop_progress") + " WHERE minion_id=?", UuidBytes.toBytes(id));
                tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=?", UuidBytes.toBytes(id));
            }
            tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_uuid=?", UuidBytes.toBytes(townUuid));
            return null;
        });
    }

    /** Final authoritative town delete plus verification of every child row known at purge start. */
    public void purgeTownVerified(UUID townUuid, long townId) {
        java.util.LinkedHashSet<UUID> minionIds = new java.util.LinkedHashSet<>();
        db.tx(tx -> {
            if (townId > 0L && townUuid != null) {
                minionIds.addAll(tx.query("SELECT id FROM " + tx.t("minions") + " WHERE town_id=? OR town_uuid=?",
                        rs -> UuidBytes.fromBytes(rs.getBytes("id")), townId, UuidBytes.toBytes(townUuid)));
            } else if (townId > 0L) {
                minionIds.addAll(tx.query("SELECT id FROM " + tx.t("minions") + " WHERE town_id=?",
                        rs -> UuidBytes.fromBytes(rs.getBytes("id")), townId));
            } else if (townUuid != null) {
                minionIds.addAll(tx.query("SELECT id FROM " + tx.t("minions") + " WHERE town_uuid=?",
                        rs -> UuidBytes.fromBytes(rs.getBytes("id")), UuidBytes.toBytes(townUuid)));
            }
            for (UUID id : minionIds) {
                byte[] bytes = UuidBytes.toBytes(id);
                tx.update("DELETE FROM " + tx.t("minion_storage") + " WHERE minion_id=?", bytes);
                tx.update("DELETE FROM " + tx.t("minion_drop_progress") + " WHERE minion_id=?", bytes);
                tx.update("DELETE FROM " + tx.t("minion_upgrades") + " WHERE minion_id=?", bytes);
            }
            if (townId > 0L && townUuid != null) {
                tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_id=? OR town_uuid=?", townId, UuidBytes.toBytes(townUuid));
            } else if (townId > 0L) {
                tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_id=?", townId);
            } else if (townUuid != null) {
                tx.update("DELETE FROM " + tx.t("minions") + " WHERE town_uuid=?", UuidBytes.toBytes(townUuid));
            }
            if (townId > 0L) {
                tx.update("DELETE FROM " + tx.t("town_minion_stats") + " WHERE town_id=?", townId);
                tx.update("DELETE FROM " + tx.t("minion_audit_log") + " WHERE town_id=?", townId);
            }
            return null;
        });
        verifyChildIdsPurged(minionIds);
        verifyTownPurged(townUuid, townId);
    }

    private void verifyChildIdsPurged(java.util.Collection<UUID> ids) {
        int remaining = 0;
        for (UUID id : ids) {
            byte[] bytes = UuidBytes.toBytes(id);
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_storage") + " WHERE minion_id=?", rs -> rs.getInt("c"), bytes).orElse(0);
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_drop_progress") + " WHERE minion_id=?", rs -> rs.getInt("c"), bytes).orElse(0);
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_upgrades") + " WHERE minion_id=?", rs -> rs.getInt("c"), bytes).orElse(0);
        }
        if (remaining != 0) throw new IllegalStateException("Minion child purge verification failed remaining=" + remaining);
    }

    public void verifyTownPurged(UUID townUuid, long townId) {
        int remaining = 0;
        if (townUuid != null && townId > 0L) {
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minions") + " WHERE town_uuid=? OR town_id=?", rs -> rs.getInt("c"), UuidBytes.toBytes(townUuid), townId).orElse(0);
        } else if (townUuid != null) {
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minions") + " WHERE town_uuid=?", rs -> rs.getInt("c"), UuidBytes.toBytes(townUuid)).orElse(0);
        } else if (townId > 0L) {
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minions") + " WHERE town_id=?", rs -> rs.getInt("c"), townId).orElse(0);
        }
        if (townId > 0L) {
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("town_minion_stats") + " WHERE town_id=?", rs -> rs.getInt("c"), townId).orElse(0);
            remaining += db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_audit_log") + " WHERE town_id=?", rs -> rs.getInt("c"), townId).orElse(0);
        }
        // Global orphan scanning is a separate maintenance concern. A stale orphan from an
        // unrelated historical town must not make this town's otherwise complete purge retry forever.
        if (remaining != 0) throw new IllegalStateException("Minion purge verification failed town=" + townUuid + " remaining=" + remaining);
    }

    public int countChildOrphans() {
        int storage = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_storage") + " s LEFT JOIN " + db.t("minions") + " m ON m.id=s.minion_id WHERE m.id IS NULL", rs -> rs.getInt("c")).orElse(0);
        int drops = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_drop_progress") + " d LEFT JOIN " + db.t("minions") + " m ON m.id=d.minion_id WHERE m.id IS NULL", rs -> rs.getInt("c")).orElse(0);
        int upgrades = db.queryOne("SELECT COUNT(*) AS c FROM " + db.t("minion_upgrades") + " u LEFT JOIN " + db.t("minions") + " m ON m.id=u.minion_id WHERE m.id IS NULL", rs -> rs.getInt("c")).orElse(0);
        return storage + drops + upgrades;
    }

    public Optional<Long> findInternalTownId(UUID townUuid) {
        Optional<Long> fromMinions = db.queryOne("SELECT town_id FROM " + db.t("minions") + " WHERE town_uuid=? LIMIT 1", rs -> rs.getLong("town_id"), UuidBytes.toBytes(townUuid));
        if (fromMinions.isPresent()) {
            return fromMinions;
        }
        // Cleanup jobs deliberately outlive the HexTowns core row. This fallback keeps
        // retry of town_minion_stats/audit cleanup possible even when HexMinions was
        // unavailable during the original core delete and the town had zero minion rows.
        try {
            Optional<Long> fromCleanupJob = db.queryOne("SELECT internal_town_id FROM " + db.t("town_cleanup_jobs") + " WHERE town_uuid=? LIMIT 1",
                    rs -> rs.getLong("internal_town_id"), UuidBytes.toBytes(townUuid));
            if (fromCleanupJob.isPresent()) return fromCleanupJob;
        } catch (RuntimeException ignored) {
            // Older HexTowns schema: fall through to the live core table.
        }
        try {
            return db.queryOne("SELECT id FROM " + db.t("towns") + " WHERE uuid=? LIMIT 1", rs -> rs.getLong("id"), UuidBytes.toBytes(townUuid));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void audit(UUID minionId, long townId, UUID actor, String action, String data) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT);
        if (!AUDITED_ACTIONS.contains(normalizedAction)) {
            return;
        }
        db.update("INSERT INTO " + db.t("minion_audit_log") + " (minion_id, town_id, actor_uuid, action, data_json, created_at) " +
                        "SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM " + db.t("towns") + " WHERE id=? AND status='ACTIVE')",
                minionId == null ? null : UuidBytes.toBytes(minionId), townId, actor == null ? null : UuidBytes.toBytes(actor), normalizedAction, data, System.currentTimeMillis(), townId);
    }

    public int purgeAuditOlderThan(long cutoffMillis) {
        return db.update("DELETE FROM " + db.t("minion_audit_log") + " WHERE created_at < ?", cutoffMillis);
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

