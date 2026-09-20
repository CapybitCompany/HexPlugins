package hex.minigames.game.jumprope;

import hex.minigames.model.BlockPosition;
import org.bukkit.util.BoundingBox;
import java.util.*;

/** Rigid U-shaped rope rotating about its longitudinal Z axis, with swept box collision. */
public final class RopeGeometry {
    // Five centimetres of edge forgiveness allow a normal jump to clear rotating cube corners.
    private static final double COLLISION_HALF_SIZE = 0.45;
    private final int x;
    private final int pivot;
    private final int period;
    private final int radius;
    private final List<BlockPosition> blocks;

    public RopeGeometry(int x, int pivot, int bottom, int nearZ, int farZ, int period) {
        this.x = x;
        this.pivot = pivot;
        this.period = period;
        this.radius = pivot - bottom;
        List<BlockPosition> positions = new ArrayList<>();
        for (int z = farZ; z <= nearZ; z++) positions.add(new BlockPosition(x, bottom, z));
        for (int y = bottom + 1; y <= pivot; y++) {
            positions.add(new BlockPosition(x, y, nearZ));
            positions.add(new BlockPosition(x, y, farZ));
        }
        blocks = List.copyOf(positions);
    }

    public List<BlockPosition> blocks() { return blocks; }
    public double angle(double tick) { return tick * Math.PI * 2 / period; }
    public double axisX() { return x + 0.5; }
    public double axisY() { return pivot + 0.5; }

    public Point center(BlockPosition block, double tick) {
        double angle = angle(tick);
        double offset = block.y() - pivot;
        return new Point(axisX() - offset * Math.sin(angle), axisY() + offset * Math.cos(angle), block.z() + 0.5);
    }

    /** Samples the angular sweep and player's movement, then tests rotated cubes using four separating axes. */
    public Optional<Hit> collision(BoundingBox from, BoundingBox to, long previousTick, long tick) {
        double angularDistance = Math.abs(angle(tick) - angle(previousTick));
        double playerDistance = Math.sqrt(Math.pow(to.getCenterX() - from.getCenterX(), 2)
                + Math.pow(to.getCenterY() - from.getCenterY(), 2) + Math.pow(to.getCenterZ() - from.getCenterZ(), 2));
        int steps = Math.max(1, (int) Math.ceil((angularDistance * (radius + 1) + playerDistance) / 0.15));
        steps = Math.min(200, steps);
        for (int i = 0; i <= steps; i++) {
            double fraction = i / (double) steps;
            double at = previousTick + (tick - previousTick) * fraction;
            double angle = angle(at), cos = Math.cos(angle), sin = Math.sin(angle);
            double px = from.getCenterX() + (to.getCenterX() - from.getCenterX()) * fraction;
            double py = from.getCenterY() + (to.getCenterY() - from.getCenterY()) * fraction;
            double pz = from.getCenterZ() + (to.getCenterZ() - from.getCenterZ()) * fraction;
            double hx = Math.max(from.getWidthX(), to.getWidthX()) / 2;
            double hy = Math.max(from.getHeight(), to.getHeight()) / 2;
            double hz = Math.max(from.getWidthZ(), to.getWidthZ()) / 2;
            for (BlockPosition block : blocks) {
                Point center = center(block, at);
                double dx = px - center.x(), dy = py - center.y();
                if (Math.abs(pz - center.z()) >= hz + COLLISION_HALF_SIZE) continue;
                double extent = (Math.abs(cos) + Math.abs(sin)) * COLLISION_HALF_SIZE;
                if (Math.abs(dx) >= hx + extent || Math.abs(dy) >= hy + extent) continue;
                if (Math.abs(dx * cos + dy * sin) >= COLLISION_HALF_SIZE + hx * Math.abs(cos) + hy * Math.abs(sin)) continue;
                if (Math.abs(-dx * sin + dy * cos) >= COLLISION_HALF_SIZE + hx * Math.abs(sin) + hy * Math.abs(cos)) continue;
                double tangentX = -(block.y() - pivot) * cos;
                double sign = Math.abs(tangentX) > 0.01 ? Math.signum(tangentX) : (dx < 0 ? -1 : 1);
                return Optional.of(new Hit(sign));
            }
        }
        return Optional.empty();
    }

    public record Point(double x, double y, double z) { }
    public record Hit(double horizontalSign) { }
}
