package hex.minigames.game.jumprope;

import hex.minigames.model.BlockPosition;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RopeGeometryTest {
    private final RopeGeometry rope = new RopeGeometry(450, 18, 13, -57, -82, 100);

    @Test
    void screenshotsProduceThirtySixBlocksAndFullRotationReturnsToStart() {
        assertEquals(36, rope.blocks().size());
        var lower = new BlockPosition(450, 13, -70);
        var center = rope.center(lower, 0);
        assertEquals(450.5, center.x());
        assertEquals(13.5, center.y());
        assertEquals(-69.5, center.z());
        assertEquals(455.5, rope.center(lower, 25).x(), 0.00001);
        assertEquals(18.5, rope.center(lower, 25).y(), 0.00001);
        assertEquals(23.5, rope.center(lower, 50).y(), 0.00001);
        assertEquals(center.x(), rope.center(lower, 100).x(), 0.00001);
        assertEquals(center.y(), rope.center(lower, 100).y(), 0.00001);
    }

    @Test
    void standingOnDeckIsHitButJumpingAboveGlassIsSafe() {
        BoundingBox standing = player(450.5, 13, -70);
        assertTrue(rope.collision(standing, standing, 0, 0).isPresent());
        BoundingBox jumping = player(450.5, 14.2, -70);
        assertTrue(rope.collision(jumping, jumping, 0, 0).isEmpty());
        assertTrue(rope.collision(standing, standing, 25, 25).isEmpty());
        BoundingBox lobby = player(450.5, 12, -52);
        assertTrue(rope.collision(lobby, lobby, 0, 100).isEmpty());
    }

    @Test
    void sweepDetectsContactsBetweenFramesAndPushesWithRotation() {
        BoundingBox standing = player(450.5, 13, -70);
        assertTrue(rope.collision(standing, standing, -5, -5).isEmpty());
        assertTrue(rope.collision(standing, standing, 5, 5).isEmpty());
        assertEquals(1.0, rope.collision(standing, standing, -5, 5).orElseThrow().horizontalSign());
        var left = player(449, 13, -70);
        var right = player(452, 13, -70);
        assertTrue(rope.collision(left, right, 0, 0).isPresent());
    }

    private BoundingBox player(double x, double y, double z) {
        return new BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void loweredThreeSecondRopeCanBeClearedByAnOrdinaryTimedJump() {
        var lowered = new RopeGeometry(450, 17, 12, -57, -82, 60);
        boolean possible = false;
        for (int jumpAt = -12; jumpAt <= 0; jumpAt++) {
            double height = 0, velocity = 0;
            BoundingBox previous = player(450.5, 12, -70);
            boolean hit = false;
            for (int tick = -15; tick <= 15; tick++) {
                if (tick == jumpAt) velocity = 0.42;
                height = Math.max(0, height + velocity);
                velocity = height > 0 ? (velocity - 0.08) * 0.98 : 0;
                BoundingBox next = player(450.5, 12 + height, -70);
                hit |= lowered.collision(previous, next, tick - 1, tick).isPresent();
                previous = next;
            }
            if (!hit) possible = true;
        }
        assertTrue(possible, "A correctly timed normal jump must clear the full swept collision, not just one frame");
        var standing = player(450.5, 12, -70);
        assertTrue(lowered.collision(standing, standing, -3, 3).isPresent());
    }
}
