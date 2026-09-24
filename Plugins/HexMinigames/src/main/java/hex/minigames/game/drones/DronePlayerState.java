package hex.minigames.game.drones;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Exact round-local controller snapshot; the persistent series snapshot is the final restore authority. */
public record DronePlayerState(double scale,float flySpeed,float walkSpeed,boolean flying,boolean flight,
                               boolean invisible,boolean collidable,boolean gravity,boolean invulnerable,GameMode mode) {
    public static DronePlayerState capture(Player player) {
        return capture(player,player.getAttribute(Attribute.SCALE));
    }
    static DronePlayerState capture(Player player,org.bukkit.attribute.AttributeInstance scale) {
        return new DronePlayerState(scale.getBaseValue(),player.getFlySpeed(),player.getWalkSpeed(),
                player.isFlying(),player.getAllowFlight(),player.isInvisible(),player.isCollidable(),player.hasGravity(),player.isInvulnerable(),player.getGameMode());
    }
    public void restore(Player player) {
        restore(player,player.getAttribute(Attribute.SCALE));
    }
    public static void scale(Player player,double value) { player.getAttribute(Attribute.SCALE).setBaseValue(value); }
    public static double reach(Player player) { return Math.min(4.5,player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE).getValue()); }
    void restore(Player player,org.bukkit.attribute.AttributeInstance scaleAttribute) {
        player.setVelocity(new Vector()); player.setFallDistance(0);
        scaleAttribute.setBaseValue(scale);
        player.setInvisible(invisible); player.setCollidable(collidable); player.setGravity(gravity); player.setInvulnerable(invulnerable);
        player.setGameMode(mode); player.setFlySpeed(flySpeed); player.setWalkSpeed(walkSpeed);
        player.setAllowFlight(flight); player.setFlying(flight&&flying);
    }
}
