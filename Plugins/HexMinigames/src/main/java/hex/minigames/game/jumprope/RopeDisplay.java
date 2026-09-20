package hex.minigames.game.jumprope;

import hex.minigames.game.common.BlockChangeTracker;
import hex.minigames.model.BlockPosition;
import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.joml.Matrix4f;
import java.util.*;

/** Smooth black-glass visuals; RopeGeometry supplies matching server-side collision. */
public final class RopeDisplay {
    private final RopeGeometry geometry;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private final BlockChangeTracker originals;

    public RopeDisplay(World world, RopeGeometry geometry) {
        this.geometry = geometry;
        this.originals = new BlockChangeTracker(world.getName());
    }

    public void spawn(World world) {
        for (BlockPosition block : geometry.blocks()) {
            if (world.getBlockAt(block.x(), block.y(), block.z()).getType() == Material.BLACK_STAINED_GLASS) {
                originals.setType(block, Material.AIR);
            }
            BlockDisplay display = world.spawn(new Location(world, geometry.axisX(), geometry.axisY(), block.z() + 0.5),
                    BlockDisplay.class, entity -> {
                        entity.setBlock(Material.BLACK_STAINED_GLASS.createBlockData());
                        entity.setPersistent(false);
                        entity.setGravity(false);
                        entity.setInterpolationDuration(1);
                        entity.setDisplayWidth(44);
                        entity.setDisplayHeight(44);
                        entity.setTransformationMatrix(matrix(block, 0));
                    });
            displays.add(display);
            if (!display.isValid()) throw new IllegalStateException("Jump-rope display spawn was rejected");
        }
    }

    public void update(long tick) {
        for (int index = 0; index < displays.size(); index++) {
            BlockDisplay display = displays.get(index);
            if (!display.isValid()) throw new IllegalStateException("Jump-rope display disappeared");
            display.setInterpolationDelay(0);
            display.setTransformationMatrix(matrix(geometry.blocks().get(index), tick));
        }
    }

    private Matrix4f matrix(BlockPosition block, long tick) {
        return new Matrix4f().rotateZ((float) geometry.angle(tick))
                .translate(0, (float) (block.y() + 0.5 - geometry.axisY()), 0).translate(-0.5f, -0.5f, -0.5f);
    }

    public void clear() {
        displays.forEach(BlockDisplay::remove);
        displays.clear();
        originals.restoreAll();
    }
}
