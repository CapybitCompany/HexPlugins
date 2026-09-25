package hexposterunki.engine;

import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.persistence.BlockSnapshotEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Captures and restores the mutable block states of an outpost (doors, trapdoors, gates, levers,
 * buttons, plates, redstone) so a reset is idempotent and the fortress build itself is untouched.
 *
 * <p>Both the capture and the fire cleanup run through {@link RegionScanner}: bounded work per
 * tick, a hard volume cap and an explicit "was it truncated" answer. The previous version capped
 * the capture but still scanned the entire region synchronously when clearing fire.
 */
public final class BlockStateService {

    private final Plugin plugin;
    private final Logger logger;
    private final RegionScanner captureScanner;
    private final RegionScanner fireScanner;

    public BlockStateService(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = logger;
        this.captureScanner = new RegionScanner(plugin);
        this.fireScanner = new RegionScanner(plugin);
    }

    /**
     * Main thread, spread over several ticks. Records the block data of every configured material.
     *
     * @param onDone receives the captured entries once the scan finished (possibly truncated)
     */
    public void captureAsync(OutpostDefinition outpost, PosterunkiConfig.Protection protection,
                             Consumer<List<BlockSnapshotEntry>> onDone) {
        List<BlockSnapshotEntry> snapshot = new ArrayList<>();
        if (outpost == null || protection.snapshotMaterials().isEmpty()) {
            onDone.accept(snapshot);
            return;
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            onDone.accept(snapshot);
            return;
        }
        Set<Material> wanted = resolveMaterials(protection.snapshotMaterials());
        captureScanner.start(outpost.region(), protection.scanBlocksPerTick(), protection.maxSnapshotVolume(),
                (x, y, z) -> {
                    Block block = world.getBlockAt(x, y, z);
                    if (wanted.contains(block.getType())) {
                        snapshot.add(new BlockSnapshotEntry(outpost.world(), x, y, z,
                                block.getBlockData().getAsString()));
                    }
                },
                result -> {
                    if (result.truncated()) {
                        logger.warning("[reset] Snapshot bloków posterunku '" + outpost.id()
                                + "' przerwany po " + result.visited() + " blokach (limit "
                                + protection.maxSnapshotVolume() + "). Zwiększ protection.max-snapshot-volume,"
                                + " jeśli cała twierdza ma być przywracana.");
                    }
                    onDone.accept(snapshot);
                });
    }

    /** Main thread. Re-applies a captured snapshot; unknown block data is skipped, not fatal. */
    public int restore(List<BlockSnapshotEntry> snapshot) {
        int restored = 0;
        for (BlockSnapshotEntry entry : snapshot) {
            World world = Bukkit.getWorld(entry.world());
            if (world == null) {
                continue;
            }
            try {
                Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
                block.setBlockData(Bukkit.createBlockData(entry.blockData()), false);
                restored++;
            } catch (IllegalArgumentException exception) {
                logger.warning("[reset] Nie można przywrócić bloku " + entry.x() + "," + entry.y() + ","
                        + entry.z() + ": " + exception.getMessage());
            }
        }
        return restored;
    }

    /**
     * Main thread, spread over several ticks. Removes fire left behind by the encounter.
     *
     * @param onDone receives the number of cleared blocks when the scan finished
     */
    public void clearFireAsync(OutpostDefinition outpost, PosterunkiConfig.Protection protection,
                               Consumer<Integer> onDone) {
        if (outpost == null) {
            onDone.accept(0);
            return;
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            onDone.accept(0);
            return;
        }
        int[] cleared = {0};
        fireScanner.start(outpost.region(), protection.scanBlocksPerTick(), protection.maxResetScanVolume(),
                (x, y, z) -> {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                        block.setType(Material.AIR, false);
                        cleared[0]++;
                    }
                },
                result -> {
                    if (result.truncated()) {
                        logger.warning("[reset] Czyszczenie ognia na posterunku '" + outpost.id()
                                + "' przerwane po " + result.visited() + " blokach (limit "
                                + protection.maxResetScanVolume() + ").");
                    }
                    onDone.accept(cleared[0]);
                });
    }

    /**
     * Stops a block-state capture that is still running. A reset that starts before the capture
     * finished must not let a late capture store a snapshot for a run that is already being cleaned.
     */
    public void cancelCapture() {
        captureScanner.cancel();
    }

    /** Stops any running scan, e.g. on plugin disable. */
    public void cancelScans() {
        captureScanner.cancel();
        fireScanner.cancel();
    }

    public boolean scanning() {
        return captureScanner.running() || fireScanner.running();
    }

    private Set<Material> resolveMaterials(Set<String> materials) {
        Set<Material> resolved = new HashSet<>();
        for (String name : materials) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("[reset] Nieznany materiał w protection.snapshot-materials: " + name);
                continue;
            }
            resolved.add(material);
        }
        return resolved;
    }
}
