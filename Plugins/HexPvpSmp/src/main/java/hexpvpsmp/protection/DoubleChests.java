package hexpvpsmp.protection;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;

/**
 * Pure geometry for locating the second half of a double chest from its block
 * data. Kept dependency-free (no world/Block) so the direction logic is unit
 * testable without a live server.
 *
 * <p>Minecraft stores each chest's {@code facing} (front direction) and a
 * {@code type} of {@code SINGLE}, {@code LEFT} or {@code RIGHT}. The partner
 * half always sits on the axis perpendicular to {@code facing}:
 * {@code LEFT} pairs clockwise of the facing, {@code RIGHT} counter-clockwise.
 */
public final class DoubleChests {

    private DoubleChests() {
    }

    /**
     * The direction from a double-chest half to its partner half, or
     * {@code null} for a single chest (no partner).
     */
    public static BlockFace partnerDirection(BlockFace facing, Chest.Type type) {
        if (facing == null || type == null || type == Chest.Type.SINGLE) {
            return null;
        }
        return type == Chest.Type.LEFT ? clockwise(facing) : counterClockwise(facing);
    }

    private static BlockFace clockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> null;
        };
    }

    private static BlockFace counterClockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> null;
        };
    }
}
