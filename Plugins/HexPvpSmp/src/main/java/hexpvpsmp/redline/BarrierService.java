package hexpvpsmp.redline;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.BarrierConfig;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.region.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws a temporary client-side wall along the nearest safezone edge when a
 * combat-tagged player is blocked from entering. The wall is sent with fake
 * block changes only (never real blocks) and reverts after a configurable
 * duration by re-sending the world's real block data.
 *
 * <p>The block selection is a pure function ({@link #computeBarrierBlocks}) so
 * the geometry is unit testable without a live client.
 */
public final class BarrierService {

    private final HexPvpSmpPlugin plugin;

    public BarrierService(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Show the barrier for a player standing at (or being pushed back from) the given region edge. */
    public void showBarrier(Player player, Cuboid region, Location playerPos) {
        HexPvpConfig config = plugin.config();
        if (config == null || player == null || region == null || playerPos == null
                || playerPos.getWorld() == null) {
            return;
        }
        BarrierConfig barrier = config.safezones().barrier();
        if (!barrier.enabled()) {
            return;
        }
        World world = playerPos.getWorld();
        List<int[]> blocks = computeBarrierBlocks(region,
                playerPos.getBlockX(), playerPos.getBlockY(), playerPos.getBlockZ(),
                barrier.radius(), barrier.height());
        BlockData fake = barrier.material().createBlockData();

        List<Location> shown = new ArrayList<>(blocks.size());
        for (int[] b : blocks) {
            Location loc = new Location(world, b[0], b[1], b[2]);
            player.sendBlockChange(loc, fake);
            shown.add(loc);
        }

        // Revert the fake blocks after the configured duration.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (Location loc : shown) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }, barrier.durationTicks());
    }

    /**
     * Computes the fake-block coordinates for a wall along the region edge
     * nearest to the player. The wall runs {@code radius} blocks to each side of
     * the player along the edge and is {@code height} blocks tall, starting at
     * the player's feet. Pure function — no world access.
     */
    public static List<int[]> computeBarrierBlocks(Cuboid c, int px, int py, int pz,
                                                   int radius, int height) {
        List<int[]> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        double dxMin = Math.abs(px - c.minX());
        double dxMax = Math.abs(c.maxX() - px);
        double dzMin = Math.abs(pz - c.minZ());
        double dzMax = Math.abs(c.maxZ() - pz);
        double min = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));

        boolean onXWall = (min == dxMin || min == dxMax);
        if (onXWall) {
            int wallX = (int) Math.round(min == dxMin ? c.minX() : c.maxX());
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = py; y < py + height; y++) {
                    out.add(new int[]{wallX, y, z});
                }
            }
        } else {
            int wallZ = (int) Math.round(min == dzMin ? c.minZ() : c.maxZ());
            for (int x = px - radius; x <= px + radius; x++) {
                for (int y = py; y < py + height; y++) {
                    out.add(new int[]{x, y, wallZ});
                }
            }
        }
        return out;
    }
}
