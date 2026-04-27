package hex.iceberg.service;

import hex.iceberg.config.IcebergConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Visual ice explosion effect when the iceberg reaches its target.
 * Spawns FallingBlock entities that fly upward and vanish after a few seconds.
 */
public class IceburstEffect {

    private static final Material[] BURST_MATERIALS = {
            Material.ICE,
            Material.PACKED_ICE,
            Material.BLUE_ICE
    };

    /**
     * Plays the iceburst effect at the given center location.
     *
     * @param plugin the owning plugin (for scheduler)
     * @param world  target world
     * @param centerX iceberg center X at arrival
     * @param centerY iceberg center Y at arrival
     * @param centerZ iceberg center Z at arrival
     * @param config  iceberg config (iceburst section)
     */
    public static void play(JavaPlugin plugin, World world, int centerX, int centerY, int centerZ,
                            IcebergConfig config) {
        if (!config.iceburstEnabled()) {
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = config.iceburstBlockCount();
        double spread = config.iceburstSpreadXZ();
        double minVY = config.iceburstMinVelocityY();
        double maxVY = config.iceburstMaxVelocityY();

        // Spread spawn positions across the top surface of the iceberg
        int rx = Math.max(1, config.radiusX() - 1);
        int rz = Math.max(1, config.radiusZ() - 1);

        List<FallingBlock> spawned = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            double offsetX = rng.nextDouble(-rx, rx + 1);
            double offsetZ = rng.nextDouble(-rz, rz + 1);
            double offsetY = rng.nextDouble(0.5, config.radiusY() + 1.0);

            Location spawnLoc = new Location(world,
                    centerX + offsetX,
                    centerY + offsetY,
                    centerZ + offsetZ);

            Material material = BURST_MATERIALS[rng.nextInt(BURST_MATERIALS.length)];

            try {
                FallingBlock fb = world.spawn(spawnLoc, FallingBlock.class, entity -> {
                    entity.setBlockData(material.createBlockData());
                    entity.setDropItem(false);
                    entity.setHurtEntities(false);
                    entity.setCancelDrop(true);
                    entity.setGravity(false);
                });

                double vx = rng.nextDouble(-spread, spread);
                double vy = rng.nextDouble(minVY, maxVY);
                double vz = rng.nextDouble(-spread, spread);
                fb.setVelocity(new Vector(vx, vy, vz));

                spawned.add(fb);
            } catch (Exception ex) {
                // Chunk not loaded or world issue — skip this block silently.
            }
        }

        // Remove all burst entities after configured ticks
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (FallingBlock fb : spawned) {
                if (fb.isValid()) {
                    fb.remove();
                }
            }
            spawned.clear();
        }, config.iceburstRemoveAfterTicks());
    }
}


