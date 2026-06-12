package hexpvpsmp.combat;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.ui.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;

/**
 * Cancels PvP damage inside any safezone; outside, tags both players.
 * Cancelled hits never tag, per spec.
 */
public final class CombatListener implements Listener {

    private final HexPvpSmpPlugin plugin;

    public CombatListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        ProtectionService protection = plugin.protectionService();
        MessageService messages = plugin.messageService();

        // Either side inside a safezone -> no PvP.
        boolean victimInSafezone = protection.isInSafezone(victim.getLocation());
        boolean attackerInSafezone = protection.isInSafezone(attacker.getLocation());
        if (victimInSafezone || attackerInSafezone) {
            event.setCancelled(true);
            messages.sendChat(attacker, config.safezones().pvpDenyMessage());
            return;
        }

        // Bypass-permission attackers don't tag (they can still inflict damage).
        CombatTagService tagger = plugin.combatTagService();
        if (!PermissionGate.bypasses(attacker)) {
            tagger.tag(attacker);
        }
        if (!PermissionGate.bypasses(victim)) {
            tagger.tag(victim);
        }
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile proj) {
            ProjectileSource source = proj.getShooter();
            if (source instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
