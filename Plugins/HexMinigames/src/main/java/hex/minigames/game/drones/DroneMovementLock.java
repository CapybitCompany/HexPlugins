package hex.minigames.game.drones;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

/** Client-synchronized movement settings, rather than repeated move cancellation/teleportation. */
public final class DroneMovementLock {
    private final Player player;
    private final float speed;
    private final AttributeInstance jump;
    private final AttributeModifier modifier;
    private boolean restored;
    public DroneMovementLock(Player player,NamespacedKey key) {
        this(player,player.getAttribute(Attribute.JUMP_STRENGTH),new AttributeModifier(key,-1,AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
    DroneMovementLock(Player player,AttributeInstance jump,AttributeModifier modifier) {
        this.player=player; speed=player.getWalkSpeed(); this.jump=jump; this.modifier=modifier;
        player.setWalkSpeed(0);
        player.setVelocity(new org.bukkit.util.Vector());
        if(jump!=null) jump.addTransientModifier(modifier);
    }
    public void restore() {
        if(restored) return; restored=true;
        player.setWalkSpeed(speed);
        if(jump!=null) jump.removeModifier(modifier);
    }
}
