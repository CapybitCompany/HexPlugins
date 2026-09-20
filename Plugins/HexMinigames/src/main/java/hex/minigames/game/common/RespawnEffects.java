package hex.minigames.game.common;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Displays the penalty at the failed attempt's location, before the queued respawn. */
public final class RespawnEffects {
    private RespawnEffects() { }

    public static void explosion(Player player) {
        Location impact = player.getLocation().add(0, 1, 0);
        if (impact.getWorld() == null) return;
        impact.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1);
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
    }
}
