package hex.minigames.game.drones;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/** Native 1.21.11 mannequin, with the owner's resolved skin, left at the assigned station. */
public final class DroneBody {
    private Mannequin body;
    public DroneBody(Player owner, Location station) {
        var profile=ResolvableProfile.resolvableProfile(owner.getPlayerProfile());
        body=station.getWorld().spawn(station,Mannequin.class,entity -> {
            entity.setPersistent(false); entity.setInvulnerable(true); entity.setGravity(false);
            entity.setImmovable(true); entity.setCollidable(false); entity.setSilent(true);
            entity.setProfile(profile); entity.setSkinParts(owner.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS));
            entity.setDescription(Component.empty()); entity.customName(Component.text(owner.getName()));
            entity.setCustomNameVisible(true); entity.setRemoveWhenFarAway(false);
        });
    }
    public void remove() { if(body!=null) { body.remove(); body=null; } }
}
