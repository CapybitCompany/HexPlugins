package hex.minigames.game.elytra;
import hex.minigames.model.BlockPosition;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RingMaskTest {
    @Test void countsAnyRingOnceAndKeepsPlayersIndependent() {
        Map<BlockPosition,Boolean> cells = new HashMap<>(plus(10)); cells.putAll(plus(20));
        var runtime = new ElytraRuntime(RingMaskBuilder.build(cells,List.of(new BlockPosition(10,1,1),new BlockPosition(20,1,1))));
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        assertEquals(1, runtime.move(player,true,25,1.5,1.5,15,1.5,1.5,1));
        assertEquals(Set.of(1), runtime.passedRings(player));
        assertEquals(Set.of(), runtime.passedRings(other));
        assertFalse(runtime.finished(player));
        assertEquals(0, runtime.move(player,true,15,1.5,1.5,25,1.5,1.5,2));
        assertEquals(1, runtime.progress(player));
        assertEquals(1, runtime.move(other,true,0,1.5,1.5,15,1.5,1.5,3));
        assertEquals(Set.of(0), runtime.passedRings(other));
        assertEquals(1, runtime.move(player,true,15,1.5,1.5,0,1.5,1.5,4));
        assertEquals(Set.of(0,1), runtime.passedRings(player));
        assertTrue(runtime.finished(player));
        assertEquals(4, runtime.finishTick(player));
        assertEquals(0, runtime.move(player,true,0,1.5,1.5,30,1.5,1.5,5));
        assertEquals(4, runtime.finishTick(player));
    }
    @Test void crossesRealArenaCoordinateInBothDirectionsOnEveryAxis() {
        var marker = new BlockPosition(76,-18,133);
        for (var axis : RingDefinition.Axis.values()) {
            var ring = new RingDefinition(marker, axis, Set.of(marker));
            double[] start = {76.5,-17.5,133.5}, end = start.clone();
            start[axis.ordinal()] -= 10;
            end[axis.ordinal()] += 10;
            assertTrue(ring.crossing(start[0],start[1],start[2],end[0],end[1],end[2]).isPresent());
            assertTrue(ring.crossing(end[0],end[1],end[2],start[0],start[1],start[2]).isPresent());
        }
    }
    private Map<BlockPosition,Boolean> plus(int x) {
        return Map.of(new BlockPosition(x,1,1),true, new BlockPosition(x,0,1),false,new BlockPosition(x,2,1),false,
                new BlockPosition(x,1,0),false,new BlockPosition(x,1,2),false);
    }
    @Test void fastSegmentUsesIrregularMaskNotItsBoundingRectangle() {
        var ring = RingMaskBuilder.build(plus(10),List.of(new BlockPosition(10,1,1))).getFirst();
        assertEquals(RingDefinition.Axis.X,ring.axis());
        assertEquals(5,ring.passableCells().size());
        assertTrue(ring.crossing(0,1.5,1.5,30,1.5,1.5).isPresent());
        assertTrue(ring.crossing(0,2.7,2.7,30,2.7,2.7).isEmpty());
        assertTrue(ring.crossing(0,4,1.5,30,4,1.5).isEmpty());
        assertTrue(ring.crossing(10.5,0,0,10.5,2,2).isEmpty());
    }
    @Test void eachRingInfersItsOwnPlaneAndKeepsExplicitOrder() {
        Map<BlockPosition,Boolean> cells = new HashMap<>(plus(10));
        var zMarker = new BlockPosition(31,1,5);
        cells.put(zMarker,true);
        cells.put(new BlockPosition(30,1,5),false);
        cells.put(new BlockPosition(31,2,5),false);
        var yMarker = new BlockPosition(41,8,5);
        cells.put(yMarker,true);
        cells.put(new BlockPosition(40,8,5),false);
        cells.put(new BlockPosition(41,8,6),false);
        var rings = RingMaskBuilder.build(cells,List.of(zMarker,new BlockPosition(10,1,1),yMarker));
        assertEquals(List.of(RingDefinition.Axis.Z,RingDefinition.Axis.X,RingDefinition.Axis.Y),rings.stream().map(RingDefinition::axis).toList());
        assertTrue(rings.get(2).crossing(41.5,0,5.5,41.5,20,5.5).isPresent());
    }
    @Test void rejectsMissingOrderConnectedCentresAndOrphanWoolBeforeEditingMap() {
        assertThrows(IllegalArgumentException.class,() -> RingMaskBuilder.build(plus(10),List.of()));
        var cells = new HashMap<>(plus(10));
        cells.put(new BlockPosition(10,2,1),true);
        assertThrows(IllegalArgumentException.class,() -> RingMaskBuilder.build(cells,List.of(new BlockPosition(10,1,1),new BlockPosition(10,2,1))));
        cells.put(new BlockPosition(10,2,1),false);
        cells.put(new BlockPosition(99,2,1),false);
        assertThrows(IllegalArgumentException.class,() -> RingMaskBuilder.build(cells,List.of(new BlockPosition(10,1,1))));
    }
    @Test void unorderedProgressSurvivesRetriesAndOnlyFinishersGetPlacementPoints() {
        Map<BlockPosition,Boolean> cells = new HashMap<>(plus(10)); cells.putAll(plus(20));
        var runtime = new ElytraRuntime(RingMaskBuilder.build(cells,List.of(new BlockPosition(10,1,1),new BlockPosition(20,1,1))));
        UUID first = UUID.randomUUID();
        assertEquals(1,runtime.move(first,true,15,1.5,1.5,25,1.5,1.5,1));
        assertEquals(0,runtime.move(first,false,0,1.5,1.5,15,1.5,1.5,2));
        assertEquals(1,runtime.move(first,true,0,1.5,1.5,15,1.5,1.5,3));
        assertEquals(0,runtime.move(first,true,0,1.5,1.5,15,1.5,1.5,4));
        assertEquals(0,runtime.move(first,true,15,1.5,1.5,25,1.5,1.5,5));
        assertEquals(3,runtime.points(first));
        for (int place = 2; place <= 9; place++) {
            UUID player = UUID.randomUUID();
            assertEquals(2,runtime.move(player,true,30,1.5,1.5,0,1.5,1.5,10+place));
            assertEquals(place <= 5 ? 2 : place <= 8 ? 1 : 0,runtime.points(player));
        }
    }
}
