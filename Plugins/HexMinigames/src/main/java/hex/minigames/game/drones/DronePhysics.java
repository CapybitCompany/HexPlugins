package hex.minigames.game.drones;

import org.bukkit.Input;
import org.bukkit.util.Vector;

/** Tick-based arcade inertia. The client supplies buttons, never the authoritative velocity. */
public final class DronePhysics {
    public record Controls(boolean forward,boolean backward,boolean left,boolean right,boolean up,boolean down,boolean boost) {
        public static final Controls NONE = new Controls(false,false,false,false,false,false,false);
        public static Controls of(Input input) { return new Controls(input.isForward(),input.isBackward(),input.isLeft(),input.isRight(),input.isJump(),input.isSneak(),input.isSprint()); }
    }
    private Vector velocity = new Vector();
    private long boostUntil, readyAt;
    private boolean previousBoost;
    public Vector tick(Controls input,Vector direction,long tick,DronesConfig.Physics config) {
        if(input.boost && !previousBoost && tick>=readyAt) { boostUntil=tick+config.boostDuration(); readyAt=tick+config.boostCooldown(); }
        previousBoost=input.boost;
        double boost=tick<boostUntil?config.boostMultiplier():1;
        Vector forward=direction.clone().normalize();
        Vector right=new Vector(-forward.getZ(),0,forward.getX());
        if(right.lengthSquared()>0.0001) right.normalize();
        // Minecraft yaw 0 looks south: left is +X and right is -X.
        Vector thrust=forward.multiply((input.forward?1:0)-(input.backward?1:0))
                .add(right.multiply((input.right?1:0)-(input.left?1:0)));
        if(thrust.lengthSquared()>1) thrust.normalize();
        velocity.multiply(config.drag()).add(thrust.multiply(config.acceleration()*boost));
        velocity.setY(velocity.getY()+((input.up?1:0)-(input.down?1:0))*config.verticalAcceleration()*boost);
        double horizontal=Math.hypot(velocity.getX(),velocity.getZ()), max=config.maxSpeed()*boost;
        if(horizontal>max) { velocity.setX(velocity.getX()*max/horizontal); velocity.setZ(velocity.getZ()*max/horizontal); }
        velocity.setY(Math.clamp(velocity.getY(),-config.maxVerticalSpeed()*boost,config.maxVerticalSpeed()*boost));
        return velocity.clone();
    }
    public void stop() { velocity.zero(); }
    public long readyAt() { return readyAt; }
    public static boolean confined(hex.minigames.model.CuboidRegion region,org.bukkit.Location at,double scale) {
        double radius=Math.max(.3*scale,.42), height=1.8*scale;
        return region.contains(at)&&at.getX()-radius>=region.minX()&&at.getX()+radius<=region.maxX()+1
                &&at.getZ()-radius>=region.minZ()&&at.getZ()+radius<=region.maxZ()+1
                &&at.getY()+height<=region.maxY()+1;
    }
}
