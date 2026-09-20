package hex.minigames.game.elytra;
import hex.minigames.model.BlockPosition;
import java.util.*;

/** Flood fills face-connected wool/centre markers without reading or inferring the decorative frame. */
public final class RingMaskBuilder {
    private RingMaskBuilder() { }
    public static List<RingDefinition> build(Map<BlockPosition, Boolean> markers, List<BlockPosition> order) {
        Set<BlockPosition> pink = new HashSet<>();
        markers.forEach((p, centre) -> { if (centre) pink.add(p); });
        Set<BlockPosition> configured = new HashSet<>(order);
        Set<BlockPosition> missingFromConfig = new HashSet<>(pink);
        missingFromConfig.removeAll(configured);
        Set<BlockPosition> missingOnMap = new HashSet<>(configured);
        missingOnMap.removeAll(pink);
        Set<BlockPosition> duplicates = new HashSet<>(), seen = new HashSet<>();
        for (BlockPosition marker : order) if (!seen.add(marker)) duplicates.add(marker);
        if (order.isEmpty() || !duplicates.isEmpty() || !missingFromConfig.isEmpty() || !missingOnMap.isEmpty()) {
            throw new IllegalArgumentException("Elytra: PINK_CONCRETE na mapie=" + pink.size()
                    + ", wpisy ring-order=" + order.size() + "; brak w konfiguracji=" + missingFromConfig
                    + "; brak na mapie=" + missingOnMap + "; powtorzone wpisy=" + duplicates);
        }
        Set<BlockPosition> visited = new HashSet<>();
        List<RingDefinition> rings = new ArrayList<>();
        for (BlockPosition centre : order) {
            if (visited.contains(centre)) throw new IllegalArgumentException("Connected mask contains multiple pink markers: " + centre);
            Set<BlockPosition> cells = new HashSet<>();
            Deque<BlockPosition> queue = new ArrayDeque<>();
            queue.add(centre); visited.add(centre);
            while (!queue.isEmpty()) {
                BlockPosition current = queue.removeFirst();
                cells.add(current);
                for (BlockPosition neighbor : neighbors(current)) if (markers.containsKey(neighbor) && visited.add(neighbor)) queue.add(neighbor);
            }
            if (cells.stream().filter(pink::contains).count() != 1) throw new IllegalArgumentException("Each connected mask needs exactly one pink marker: " + centre);
            List<RingDefinition.Axis> axes = new ArrayList<>();
            if (cells.stream().allMatch(p -> p.x() == centre.x())) axes.add(RingDefinition.Axis.X);
            if (cells.stream().allMatch(p -> p.y() == centre.y())) axes.add(RingDefinition.Axis.Y);
            if (cells.stream().allMatch(p -> p.z() == centre.z())) axes.add(RingDefinition.Axis.Z);
            if (axes.size() != 1) throw new IllegalArgumentException("Mask must occupy one unambiguous plane: " + centre);
            rings.add(new RingDefinition(centre, axes.getFirst(), cells));
        }
        if (visited.size() != markers.size()) throw new IllegalArgumentException("Unassigned YELLOW_WOOL in the Elytra region");
        return List.copyOf(rings);
    }
    public static List<BlockPosition> neighbors(BlockPosition p) {
        return List.of(new BlockPosition(p.x()+1,p.y(),p.z()), new BlockPosition(p.x()-1,p.y(),p.z()),
                new BlockPosition(p.x(),p.y()+1,p.z()), new BlockPosition(p.x(),p.y()-1,p.z()),
                new BlockPosition(p.x(),p.y(),p.z()+1), new BlockPosition(p.x(),p.y(),p.z()-1));
    }
}
