package hex.iceberg.service;

import hex.core.api.ui.UiService;
import hex.iceberg.IcebergPlugin;
import hex.iceberg.config.IcebergConfig;
import hex.iceberg.model.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class IcebergService {

    private final IcebergPlugin plugin;
    private IcebergConfig config;

    private BukkitTask task;
    private final Set<BlockPos> placedBlocks = new HashSet<>();

    private boolean running;
    private int elapsedTicks;
    private int postArrivalTicks;

    private int lastCenterX;
    private int lastCenterY;
    private int lastCenterZ;

    private List<BlockPos> localShape;

    public IcebergService(IcebergPlugin plugin, IcebergConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.localShape = buildLocalShape(config);
    }

    public void updateConfig(IcebergConfig config) {
        this.config = config;
        this.localShape = buildLocalShape(config);
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        if (!running) {
            return "IDLE";
        }
        return "MOVING t=" + elapsedTicks + "/" + config.movementDurationTicks();
    }

    public void start() {
        if (running) {
            return;
        }

        World world = Bukkit.getWorld(config.world());
        if (world == null) {
            plugin.getLogger().warning("World not found: " + config.world());
            return;
        }

        clearIceberg(world);

        this.elapsedTicks = 0;
        this.postArrivalTicks = 0;
        this.running = true;

        this.lastCenterX = config.startX();
        this.lastCenterY = config.startY();
        this.lastCenterZ = config.startZ();

        placeIceberg(world, lastCenterX, lastCenterY, lastCenterZ);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(world),
                config.movementStepTicks(), config.movementStepTicks());

        broadcast(config.msgStarted(), "iceberg.started");
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }

        World world = Bukkit.getWorld(config.world());
        if (world != null) {
            clearIceberg(world);
        }

        broadcast(config.msgStopped(), "iceberg.stopped");
    }

    private void tick(World world) {
        if (!running) {
            return;
        }

        elapsedTicks += config.movementStepTicks();

        int duration = config.movementDurationTicks();
        if (elapsedTicks <= duration) {
            double progress = Math.min(1.0D, (double) elapsedTicks / (double) duration);
            int centerX = lerp(config.startX(), config.targetX(), progress);
            int centerY = lerp(config.startY(), config.targetY(), progress);
            int centerZ = lerp(config.startZ(), config.targetZ(), progress);

            if (centerX != lastCenterX || centerY != lastCenterY || centerZ != lastCenterZ) {
                clearIceberg(world);
                placeIceberg(world, centerX, centerY, centerZ);
                lastCenterX = centerX;
                lastCenterY = centerY;
                lastCenterZ = centerZ;
            }

            if (elapsedTicks >= duration) {
                IceburstEffect.play(plugin, world, lastCenterX, lastCenterY, lastCenterZ, config);
                broadcast(config.msgArrived(), "iceberg.arrived");
            }
            return;
        }

        postArrivalTicks += config.movementStepTicks();
        if (postArrivalTicks >= config.removeDelayTicks()) {
            clearIceberg(world);
            running = false;
            if (task != null) {
                task.cancel();
                task = null;
            }
            broadcast(config.msgRemoved(), "iceberg.removed");
        }
    }

    private int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private void placeIceberg(World world, int centerX, int centerY, int centerZ) {
        for (BlockPos local : localShape) {
            int x = centerX + local.x();
            int y = centerY + local.y();
            int z = centerZ + local.z();

            Material material = chooseMaterial(local);
            world.getBlockAt(x, y, z).setType(material, false);
            placedBlocks.add(new BlockPos(x, y, z));
        }
    }

    private Material chooseMaterial(BlockPos local) {
        // Spike blocks (above normal ellipsoid top)
        if (local.y() >= config.radiusY()) {
            return config.shellMaterial();
        }

        if (local.y() >= config.radiusY() - 1) {
            return config.topMaterial();
        }

        double nx = (double) local.x() / (double) config.radiusX();
        double ny = (double) local.y() / (double) config.radiusY();
        double nz = (double) local.z() / (double) config.radiusZ();
        double dist = (nx * nx) + (ny * ny) + (nz * nz);

        if (dist < 0.5D) {
            return config.coreMaterial();
        }
        return config.shellMaterial();
    }

    private void clearIceberg(World world) {
        for (BlockPos pos : placedBlocks) {
            if (world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() != Material.AIR) {
                world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(Material.AIR, false);
            }
        }
        placedBlocks.clear();
    }

    private List<BlockPos> buildLocalShape(IcebergConfig cfg) {
        Set<BlockPos> shapeSet = new HashSet<>();

        int rx = cfg.radiusX();
        int ry = cfg.radiusY();
        int rz = cfg.radiusZ();

        // Base ellipsoid
        for (int x = -rx; x <= rx; x++) {
            for (int y = -ry; y <= ry; y++) {
                for (int z = -rz; z <= rz; z++) {
                    double nx = (double) x / (double) rx;
                    double ny = (double) y / (double) ry;
                    double nz = (double) z / (double) rz;
                    if ((nx * nx) + (ny * ny) + (nz * nz) <= 1.0D) {
                        shapeSet.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        // Spike: one random tall peak on top of the ellipsoid
        if (cfg.spikeEnabled()) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int spikeR = cfg.spikeRadius();
            int spikeHeight = (int) Math.round(ry * cfg.spikeHeightMultiplier());

            // Random position on the surface, within 60% of radius so it doesn't overhang
            int spikeBaseX = rng.nextInt(-(int) (rx * 0.6), (int) (rx * 0.6) + 1);
            int spikeBaseZ = rng.nextInt(-(int) (rz * 0.6), (int) (rz * 0.6) + 1);

            // Build spike from the top of the ellipsoid upwards
            for (int dy = 0; dy <= spikeHeight; dy++) {
                // Taper: radius shrinks toward the top
                double taper = 1.0 - ((double) dy / (double) spikeHeight);
                int layerR = Math.max(0, (int) Math.round(spikeR * taper));

                for (int dx = -layerR; dx <= layerR; dx++) {
                    for (int dz = -layerR; dz <= layerR; dz++) {
                        if (dx * dx + dz * dz <= layerR * layerR + 1) {
                            shapeSet.add(new BlockPos(spikeBaseX + dx, ry + dy, spikeBaseZ + dz));
                        }
                    }
                }
            }
        }

        return new ArrayList<>(shapeSet);
    }


    /**
     * Wyslij broadcast przez HexCore UiService (jesli dostepny),
     * fallback na stary Bukkit.broadcastMessage.
     */
    private void broadcast(String fallbackMessage, String templateKey) {
        UiService ui = plugin.ui();
        if (ui != null && templateKey != null) {
            ui.broadcast(templateKey);
        } else {
            Bukkit.broadcastMessage(fallbackMessage);
        }
    }
}

