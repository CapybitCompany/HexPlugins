package hexcasino.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable slot geometry. Inventory slots are stored row-major for the active grid.
 */
public record SlotLayout(
        int reels,
        int rows,
        List<Integer> inventorySlots,
        List<WinningPattern> winningPatterns
) {
    public SlotLayout {
        if (rows != 3) {
            throw new IllegalArgumentException("Slot rows must be exactly 3");
        }
        if (reels != 1 && reels != 3 && reels != 5) {
            throw new IllegalArgumentException("Slot reels must be one of 1, 3 or 5");
        }
        Objects.requireNonNull(inventorySlots, "inventorySlots");
        Objects.requireNonNull(winningPatterns, "winningPatterns");
        if (inventorySlots.size() != reels * rows) {
            throw new IllegalArgumentException("inventorySlots size must equal reels * rows");
        }
        if (inventorySlots.stream().distinct().count() != inventorySlots.size()) {
            throw new IllegalArgumentException("inventorySlots must be unique");
        }
        inventorySlots = List.copyOf(inventorySlots);
        winningPatterns = List.copyOf(winningPatterns);
    }

    public int cellCount() {
        return reels * rows;
    }

    public int index(int x, int y) {
        if (x < 0 || x >= reels || y < 0 || y >= rows) {
            throw new IndexOutOfBoundsException("Grid point outside layout: " + x + "," + y);
        }
        return (y * reels) + x;
    }

    public int inventorySlot(GridPoint point) {
        return inventorySlots.get(index(point.x(), point.y()));
    }

    /**
     * Number of independently stopped visual columns.
     *
     * The 1-line layout is a special horizontal row of three symbols: economically it is
     * still the single-pattern 1x3 option, but visually each of its three cells is stopped
     * by a separate click. The 3x3 and 5x3 layouts stop normal vertical reels.
     */
    public int stopUnitCount() {
        return reels == 1 ? rows : reels;
    }

    /**
     * Returns row-major outcome indexes revealed/animated by one STOP unit.
     */
    public List<Integer> stopUnitCellIndexes(int stopUnit) {
        if (stopUnit < 0 || stopUnit >= stopUnitCount()) {
            throw new IndexOutOfBoundsException("Stop unit outside layout: " + stopUnit);
        }
        if (reels == 1) {
            return List.of(stopUnit);
        }
        List<Integer> indexes = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            indexes.add((row * reels) + stopUnit);
        }
        return List.copyOf(indexes);
    }

    /**
     * Builds the selectable 1x3/3x3/5x3 layouts from the configured maximum 5x3 grid.
     *
     * The 1x3 option is intentionally rendered as one horizontal payline in the middle
     * row (three visible symbols), while its logical geometry remains a single 3-cell
     * pattern. Cost/RTP still use the 1x layout, but the three visible cells stop separately.
     */
    public static SlotLayout centered(int reels, int rows, List<Integer> maximumGridSlots) {
        Objects.requireNonNull(maximumGridSlots, "maximumGridSlots");
        if (rows != 3) {
            throw new IllegalArgumentException("rows must be 3");
        }
        if (maximumGridSlots.size() != 15) {
            throw new IllegalArgumentException("maximumGridSlots must contain exactly 15 slots (5x3)");
        }
        int maxReels = 5;
        List<Integer> activeSlots = new ArrayList<>(reels * rows);

        if (reels == 1) {
            // The one-line variant is shown horizontally in the middle row:
            // [12, 13, 14] for the default 5x3 grid. Logically it is still one
            // three-cell pattern, so cost/RTP stay unchanged. Its three visible cells
            // are nevertheless stopped separately by three clicks.
            int middleRow = rows / 2;
            int startX = 1;
            for (int offset = 0; offset < rows; offset++) {
                activeSlots.add(maximumGridSlots.get((middleRow * maxReels) + startX + offset));
            }
        } else {
            int startX = (maxReels - reels) / 2;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < reels; x++) {
                    activeSlots.add(maximumGridSlots.get((y * maxReels) + startX + x));
                }
            }
        }
        return new SlotLayout(reels, rows, activeSlots, generateWinningPatterns(reels, rows));
    }

    /**
     * Generates every geometrically valid run of three consecutive grid cells on a straight line.
     * Only primitive direction vectors are used, so horizontal 1-3-5 patterns with gaps are excluded.
     */
    public static List<WinningPattern> generateWinningPatterns(int reels, int rows) {
        Map<String, WinningPattern> patterns = new LinkedHashMap<>();
        for (int dx = 0; dx < reels; dx++) {
            for (int dy = -(rows - 1); dy <= rows - 1; dy++) {
                if (dx == 0 && dy <= 0) {
                    continue;
                }
                if (dx == 0 && dy == 0) {
                    continue;
                }
                if (dx > 0 && dy == 0 && gcd(dx, 0) != 1) {
                    continue;
                }
                if (gcd(Math.abs(dx), Math.abs(dy)) != 1) {
                    continue;
                }
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < reels; x++) {
                        GridPoint p1 = new GridPoint(x, y);
                        GridPoint p2 = new GridPoint(x + dx, y + dy);
                        GridPoint p3 = new GridPoint(x + (2 * dx), y + (2 * dy));
                        if (!inside(p2, reels, rows) || !inside(p3, reels, rows)) {
                            continue;
                        }
                        List<GridPoint> points = List.of(p1, p2, p3);
                        String key = canonicalKey(points);
                        patterns.putIfAbsent(key, new WinningPattern(patternId(points), points));
                    }
                }
            }
        }
        return List.copyOf(patterns.values());
    }

    private static boolean inside(GridPoint point, int reels, int rows) {
        return point.x() >= 0 && point.x() < reels && point.y() >= 0 && point.y() < rows;
    }

    private static String patternId(List<GridPoint> points) {
        GridPoint first = points.get(0);
        GridPoint last = points.get(2);
        return "p-" + first.x() + "-" + first.y() + "_" + last.x() + "-" + last.y();
    }

    private static String canonicalKey(List<GridPoint> points) {
        String forward = key(points.get(0), points.get(1), points.get(2));
        String reverse = key(points.get(2), points.get(1), points.get(0));
        return forward.compareTo(reverse) <= 0 ? forward : reverse;
    }

    private static String key(GridPoint a, GridPoint b, GridPoint c) {
        return a.x() + ":" + a.y() + "|" + b.x() + ":" + b.y() + "|" + c.x() + ":" + c.y();
    }

    private static int gcd(int a, int b) {
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return Math.abs(a);
    }
}
