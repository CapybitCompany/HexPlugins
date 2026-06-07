package hex.core.command.region;

import hex.core.api.region.BlockPos;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class RegionPreview {
    private static final int MAX_PARTICLES_PER_DRAW = 1500;

    private RegionPreview() {}

    /** Draws cuboid edges using particles (client-side for one player). */
    public static void drawCuboidEdges(Player player, World world, BlockPos a, BlockPos b, double step) {
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);

        // expand to block corners nicely (optional)
        double minX = min.x();
        double minY = min.y();
        double minZ = min.z();
        double maxX = max.x() + 1;
        double maxY = max.y() + 1;
        double maxZ = max.z() + 1;
        int[] budget = {MAX_PARTICLES_PER_DRAW};

        // 12 edges = 4 bottom + 4 top + 4 vertical
        // bottom rectangle (y=minY)
        line(player, world, minX, minY, minZ, maxX, minY, minZ, step, budget);
        line(player, world, maxX, minY, minZ, maxX, minY, maxZ, step, budget);
        line(player, world, maxX, minY, maxZ, minX, minY, maxZ, step, budget);
        line(player, world, minX, minY, maxZ, minX, minY, minZ, step, budget);

        // top rectangle (y=maxY)
        line(player, world, minX, maxY, minZ, maxX, maxY, minZ, step, budget);
        line(player, world, maxX, maxY, minZ, maxX, maxY, maxZ, step, budget);
        line(player, world, maxX, maxY, maxZ, minX, maxY, maxZ, step, budget);
        line(player, world, minX, maxY, maxZ, minX, maxY, minZ, step, budget);

        // vertical edges
        line(player, world, minX, minY, minZ, minX, maxY, minZ, step, budget);
        line(player, world, maxX, minY, minZ, maxX, maxY, minZ, step, budget);
        line(player, world, maxX, minY, maxZ, maxX, maxY, maxZ, step, budget);
        line(player, world, minX, minY, maxZ, minX, maxY, maxZ, step, budget);
    }

    private static void line(Player player, World world,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double step, int[] budget) {

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;

        double length = Math.sqrt(dx*dx + dy*dy + dz*dz);
        int points = Math.max(1, (int) Math.floor(length / step));

        double ix = dx / points;
        double iy = dy / points;
        double iz = dz / points;

        for (int i = 0; i <= points; i++) {
            if (budget[0] <= 0) {
                return;
            }
            double x = x1 + ix * i;
            double y = y1 + iy * i;
            double z = z1 + iz * i;

            // particle only for this player
            player.spawnParticle(Particle.END_ROD, new Location(world, x, y, z), 1, 0, 0, 0, 0);
            budget[0]--;
        }
    }
}