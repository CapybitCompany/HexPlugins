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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.UUID;

/**
 * Cancels PvP damage inside any safezone; outside, tags both players.
 * Cancelled hits never tag, per spec. Also drops the combat tag on a real
 * death — combat-log-on-quit punishment lives in {@code CombatLogListener}
 * and is untouched by this.
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

        // Either side inside a spawn safezone -> no PvP. No-build zones do NOT
        // block PvP, only building.
        boolean victimProtected = protection.isPvpProtected(victim.getLocation());
        boolean attackerProtected = protection.isPvpProtected(attacker.getLocation());
        if (victimProtected || attackerProtected) {
            event.setCancelled(true);
            messages.sendChat(attacker, config.messages().pvpDenied());
            plugin.debugLog("PvP denied: " + attacker.getName() + " -> " + victim.getName() + " (safezone)");
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

    /**
     * A player who actually died should not stay combat-tagged. Clear the tag
     * (and their message cooldowns) so respawn/teleport is not blocked and the
     * actionbar stops. This is independent of the combat-log-on-quit flow.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();
        CombatTagService tagger = plugin.combatTagService();
        if (tagger != null && tagger.untag(playerId)) {
            plugin.messageService().clearCooldowns(playerId);
            plugin.debugLog("Combat tag cleared on death for " + player.getName());
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
