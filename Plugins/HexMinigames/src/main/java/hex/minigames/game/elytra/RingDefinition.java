package hex.minigames.game.elytra;
import hex.minigames.model.BlockPosition;
import java.util.*;

/** Exact authored cells are the trigger; the plane is only used to intersect fast movement. */
public record RingDefinition(BlockPosition marker, Axis axis, Set<BlockPosition> passableCells) {
    public RingDefinition { passableCells = Set.copyOf(passableCells); }
    public enum Axis { X, Y, Z }
    public OptionalDouble crossing(double x0, double y0, double z0, double x1, double y1, double z1) {
        double a = coordinate(x0, y0, z0), b = coordinate(x1, y1, z1);
        double plane = coordinate(marker.x(), marker.y(), marker.z()) + 0.5;
        if (Math.abs(b - a) < 1e-9 || (a < plane && b < plane) || (a > plane && b > plane)) return OptionalDouble.empty();
        double t = (plane - a) / (b - a);
        if (t < 0 || t > 1) return OptionalDouble.empty();
        double x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, z = z0 + (z1 - z0) * t;
        // Ten centimetres in the ring plane accommodate the gliding player's body at edges.
        for (double u : new double[]{0, -0.1, 0.1}) for (double v : new double[]{0, -0.1, 0.1}) {
            double px = x, py = y, pz = z;
            switch (axis) {
                case X -> { py += u; pz += v; }
                case Y -> { px += u; pz += v; }
                case Z -> { px += u; py += v; }
            }
            if (passableCells.contains(new BlockPosition((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)))) return OptionalDouble.of(t);
        }
        return OptionalDouble.empty();
    }
    private double coordinate(double x, double y, double z) { return switch (axis) { case X -> x; case Y -> y; case Z -> z; }; }
}
