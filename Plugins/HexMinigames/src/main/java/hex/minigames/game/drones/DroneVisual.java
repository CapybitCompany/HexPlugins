package hex.minigames.game.drones;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** One logical drone made of nine non-colliding displays. */
public final class DroneVisual {
    private final List<BlockDisplay> parts=new ArrayList<>();
    public DroneVisual(Location at,double scale) {
        try {
            part(at,Material.HEAVY_CORE,0,0.15,0,scale,scale,scale);
            for(int x:new int[]{-1,1}) for(int z:new int[]{-1,1}) {
                part(at,Material.IRON_BLOCK,x*.18,.2,z*.18,.25,.04,.07);
                part(at,Material.IRON_TRAPDOOR,x*.3,.25,z*.3,.22,.06,.22);
            }
        } catch(RuntimeException error) { remove(); throw error; }
    }
    private void part(Location at,Material material,double x,double y,double z,double sx,double sy,double sz) {
        BlockDisplay display=at.getWorld().spawn(at,BlockDisplay.class,e -> {
            e.setBlock(material.createBlockData()); e.setPersistent(false); e.setInvulnerable(true); e.setGravity(false);
            e.setTeleportDuration(1); e.setViewRange(2); e.setDisplayWidth(2); e.setDisplayHeight(2);
            e.setTransformation(new Transformation(new Vector3f((float)(x-sx/2),(float)y,(float)(z-sz/2)),new Quaternionf(),
                    new Vector3f((float)sx,(float)sy,(float)sz),new Quaternionf()));
        });
        parts.add(display);
    }
    public void move(Location at) { Location root=at.clone(); root.setPitch(0); for(BlockDisplay part:parts) part.teleport(root); }
    public void remove() { parts.forEach(Entity::remove); parts.clear(); }
    public int size() { return parts.size(); }
}
