package hex.minions.machine;

import hex.core.api.db.Db;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Trwały magazyn stanu maszyn.
 *
 * To jest istniejący runtime maszyn: przechowuje input/output/fuel/updaty/EU/progress.
 * Offline catch-up korzysta z tych samych rekordów i rozszerza je tylko o last_active_at,
 * żeby nie liczyć ponownie tego samego czasu po kolejnym chunk load.
 *
 * Dlaczego DB zamiast jednego machine-runtimes.yml:
 * - przy setkach/tysiącach maszyn zapis całego YAML co kilka sekund skaluje się liniowo po całej populacji,
 * - DB pozwala zapisywać tylko brudne rekordy w batchu,
 * - restart nie wymaga parsowania dużego pliku i nadpisywania niezmienionych maszyn.
 */
public final class MachineRuntimeRepository {
    private final Db db;

    public MachineRuntimeRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("machine_runtimes") + " (" +
                "block_key VARCHAR(160) NOT NULL," +
                "machine_id VARCHAR(64) NOT NULL," +
                "world VARCHAR(64) NOT NULL," +
                "x INT NOT NULL," +
                "y SMALLINT NOT NULL," +
                "z INT NOT NULL," +
                "input_data MEDIUMTEXT NULL," +
                "extra_input0_data MEDIUMTEXT NULL," +
                "extra_input1_data MEDIUMTEXT NULL," +
                "secondary_data MEDIUMTEXT NULL," +
                "fuel_data MEDIUMTEXT NULL," +
                "output_data MEDIUMTEXT NULL," +
                "upgrade0_data MEDIUMTEXT NULL," +
                "upgrade1_data MEDIUMTEXT NULL," +
                "upgrade2_data MEDIUMTEXT NULL," +
                "energy INT NOT NULL DEFAULT 0," +
                "recipe_id VARCHAR(128) NOT NULL DEFAULT ''," +
                "progress_seconds INT NOT NULL DEFAULT 0," +
                "last_fuel_seconds INT NOT NULL DEFAULT 0," +
                "burn_remaining_seconds INT NOT NULL DEFAULT 0," +
                "burn_eu_remaining INT NOT NULL DEFAULT 0," +
                "last_active_at BIGINT NOT NULL DEFAULT 0," +
                "accumulator_input_face VARCHAR(16) NOT NULL DEFAULT ''," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (block_key)," +
                "KEY idx_machine_id (machine_id)," +
                "KEY idx_world_chunk (world, x, z)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        try { db.update("ALTER TABLE " + db.t("machine_runtimes") + " ADD COLUMN extra_input0_data MEDIUMTEXT NULL"); } catch (Throwable ignored) { }
        try { db.update("ALTER TABLE " + db.t("machine_runtimes") + " ADD COLUMN extra_input1_data MEDIUMTEXT NULL"); } catch (Throwable ignored) { }
        try { db.update("ALTER TABLE " + db.t("machine_runtimes") + " ADD COLUMN last_active_at BIGINT NOT NULL DEFAULT 0"); } catch (Throwable ignored) { }
        try { db.update("ALTER TABLE " + db.t("machine_runtimes") + " ADD COLUMN accumulator_input_face VARCHAR(16) NOT NULL DEFAULT ''"); } catch (Throwable ignored) { }
    }

    public List<MachineRuntime> loadAll() {
        return db.query("SELECT block_key, machine_id, input_data, extra_input0_data, extra_input1_data, secondary_data, fuel_data, output_data, " +
                        "upgrade0_data, upgrade1_data, upgrade2_data, energy, recipe_id, progress_seconds, " +
                        "last_fuel_seconds, burn_remaining_seconds, burn_eu_remaining, last_active_at, accumulator_input_face, updated_at FROM " + db.t("machine_runtimes"),
                rs -> {
                    MachineRuntime runtime = new MachineRuntime(rs.getString("block_key"), rs.getString("machine_id"));
                    runtime.input(deserializeItem(rs.getString("input_data")));
                    try { runtime.extraInput(0, deserializeItem(rs.getString("extra_input0_data"))); } catch (Throwable ignored) { }
                    try { runtime.extraInput(1, deserializeItem(rs.getString("extra_input1_data"))); } catch (Throwable ignored) { }
                    runtime.secondary(deserializeItem(rs.getString("secondary_data")));
                    runtime.fuel(deserializeItem(rs.getString("fuel_data")));
                    runtime.output(deserializeItem(rs.getString("output_data")));
                    runtime.upgrade(0, deserializeItem(rs.getString("upgrade0_data")));
                    runtime.upgrade(1, deserializeItem(rs.getString("upgrade1_data")));
                    runtime.upgrade(2, deserializeItem(rs.getString("upgrade2_data")));
                    runtime.energy(rs.getInt("energy"));
                    runtime.restoreProcess(rs.getString("recipe_id"), rs.getInt("progress_seconds"));
                    runtime.restoreBurn(rs.getInt("burn_eu_remaining"), rs.getInt("burn_remaining_seconds"), rs.getInt("last_fuel_seconds"));
                    long lastActive = rs.getLong("last_active_at");
                    if (lastActive <= 0L) lastActive = rs.getLong("updated_at");
                    runtime.lastActiveAtMillis(lastActive <= 0L ? System.currentTimeMillis() : lastActive);
                    try { runtime.accumulatorInputFace(rs.getString("accumulator_input_face")); } catch (Throwable ignored) { }
                    return runtime;
                });
    }

    public void saveBatch(List<MachineRuntime> runtimes) {
        if (runtimes == null || runtimes.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>();
        for (MachineRuntime runtime : runtimes) {
            LocationParts parts = LocationParts.parse(runtime.blockKey());
            if (parts == null) continue;
            batch.add(new Object[]{
                    runtime.blockKey(), runtime.machineId(), parts.world, parts.x, parts.y, parts.z,
                    serializeItem(runtime.input()), serializeItem(runtime.extraInput(0)), serializeItem(runtime.extraInput(1)), serializeItem(runtime.secondary()), serializeItem(runtime.fuel()), serializeItem(runtime.output()),
                    serializeItem(runtime.upgrade(0)), serializeItem(runtime.upgrade(1)), serializeItem(runtime.upgrade(2)),
                    runtime.energy(), runtime.recipeId(), runtime.progressSeconds(), runtime.lastFuelSeconds(), runtime.burnRemainingSeconds(), runtime.burnEuRemaining(), runtime.lastActiveAtMillis() <= 0L ? now : runtime.lastActiveAtMillis(), runtime.accumulatorInputFace(), now
            });
        }
        if (batch.isEmpty()) return;
        db.batch("INSERT INTO " + db.t("machine_runtimes") + " (" +
                        "block_key, machine_id, world, x, y, z, input_data, extra_input0_data, extra_input1_data, secondary_data, fuel_data, output_data, " +
                        "upgrade0_data, upgrade1_data, upgrade2_data, energy, recipe_id, progress_seconds, last_fuel_seconds, " +
                        "burn_remaining_seconds, burn_eu_remaining, last_active_at, accumulator_input_face, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE machine_id=VALUES(machine_id), world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), " +
                        "input_data=VALUES(input_data), extra_input0_data=VALUES(extra_input0_data), extra_input1_data=VALUES(extra_input1_data), secondary_data=VALUES(secondary_data), fuel_data=VALUES(fuel_data), output_data=VALUES(output_data), " +
                        "upgrade0_data=VALUES(upgrade0_data), upgrade1_data=VALUES(upgrade1_data), upgrade2_data=VALUES(upgrade2_data), " +
                        "energy=VALUES(energy), recipe_id=VALUES(recipe_id), progress_seconds=VALUES(progress_seconds), " +
                        "last_fuel_seconds=VALUES(last_fuel_seconds), burn_remaining_seconds=VALUES(burn_remaining_seconds), " +
                        "burn_eu_remaining=VALUES(burn_eu_remaining), last_active_at=VALUES(last_active_at), " +
                        "accumulator_input_face=VALUES(accumulator_input_face), updated_at=VALUES(updated_at)", batch);
    }

    public void deleteBatch(List<String> blockKeys) {
        if (blockKeys == null || blockKeys.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (String key : blockKeys) {
            if (key != null && !key.isBlank()) batch.add(new Object[]{key});
        }
        if (!batch.isEmpty()) db.batch("DELETE FROM " + db.t("machine_runtimes") + " WHERE block_key=?", batch);
    }

    private String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream data = new BukkitObjectOutputStream(output)) {
                data.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            return null;
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

    private record LocationParts(String world, int x, int y, int z) {
        static LocationParts parse(String blockKey) {
            try {
                String[] parts = blockKey.split(":");
                if (parts.length != 4) return null;
                return new LocationParts(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
