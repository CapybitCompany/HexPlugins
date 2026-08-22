package hexcasino.machine;

import java.util.List;
import java.util.Objects;

public record WinningPattern(String id, List<GridPoint> points) {
    public WinningPattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(points, "points");
        if (points.size() != 3) {
            throw new IllegalArgumentException("Winning pattern must contain exactly 3 points");
        }
        points = List.copyOf(points);
        if (points.stream().distinct().count() != 3) {
            throw new IllegalArgumentException("Winning pattern must contain 3 distinct points");
        }
    }
}
