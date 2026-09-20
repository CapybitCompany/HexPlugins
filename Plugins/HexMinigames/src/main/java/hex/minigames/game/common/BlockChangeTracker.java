package hex.minigames.game.common;

import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockChangeTracker {
    private final String worldName;
    private final Map<BlockPosition, BlockData> originals = new LinkedHashMap<>();

    public BlockChangeTracker(String worldName) {
        this.worldName = worldName;
    }

    public void setType(BlockPosition position, Material material) {
        World world = world();
        if (world == null || position == null || material == null) return;
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        originals.putIfAbsent(position, block.getBlockData());
        block.setType(material, false);
    }

    public void setBlockData(BlockPosition position, BlockData blockData) {
        World world = world();
        if (world == null || position == null || blockData == null) return;
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        originals.putIfAbsent(position, block.getBlockData());
        block.setBlockData(blockData, false);
    }

    public void fill(CuboidRegion region, Material material) {
        if (region == null) return;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    setType(new BlockPosition(x, y, z), material);
                }
            }
        }
    }

    public void restoreAll() {
        World world = world();
        if (world == null) {
            originals.clear();
            return;
        }
        for (Map.Entry<BlockPosition, BlockData> entry : originals.entrySet()) {
            BlockPosition position = entry.getKey();
            world.getBlockAt(position.x(), position.y(), position.z()).setBlockData(entry.getValue(), false);
        }
        originals.clear();
    }

    public void forgetSnapshots() {
        originals.clear();
    }

    private World world() {
        return Bukkit.getWorld(worldName);
    }
}
