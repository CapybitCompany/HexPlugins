package hex.core.api.region;

import org.bukkit.Location;

public record BlockPos(int x, int y, int z) {

    public static BlockPos from(Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.min(a.z, b.z)
        );
    }

    public static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.x, b.x),
                Math.max(a.y, b.y),
                Math.max(a.z, b.z)
        );
    }

    public String toCsv() {
        return x + "," + y + "," + z;
    }

    public static BlockPos parseCsv(String csv) {
        String[] p = csv.split(",", 3);
        if (p.length != 3) throw new IllegalArgumentException("Invalid pos csv: " + csv);
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }
}
