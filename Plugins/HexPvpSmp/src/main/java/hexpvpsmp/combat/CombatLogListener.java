package hexpvpsmp.combat;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.CombatConfig;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.ui.MessageService;
import hexpvpsmp.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Detects combat-log: quit while tagged. The quitter is treated as having died:
 * inventory + armor + offhand + (optionally) experience are dropped at the quit
 * location, the stash is cleared, the death is counted, the last attacker is
 * credited with the kill, and the player is queued to respawn at spawn on their
 * next join (instead of reappearing at the logout location). Idempotent against
 * duplicate quit events, and never triggers a vanilla {@code PlayerDeathEvent}
 * (so there are no double drops).
 */
public final class CombatLogListener implements Listener {

    private final HexPvpSmpPlugin plugin;
    private final Set<UUID> processed = new HashSet<>();
    // Players who combat-logged and must respawn at spawn on their next join.
    private final Set<UUID> pendingRespawn = new HashSet<>();

    public CombatLogListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Visible for testing: whether a player is queued for a spawn respawn. */
    public boolean hasPendingRespawn(UUID playerId) {
        return pendingRespawn.contains(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        CombatTagService tagger = plugin.combatTagService();
        if (!tagger.isTagged(player)) {
            // Untagged quit: no-op, just clean up message-service buckets.
            plugin.messageService().clearCooldowns(playerId);
            return;
        }
        if (!processed.add(playerId)) {
            return;
        }

        // Capture the killer before the tag (and its state) is cleared.
        UUID killerId = tagger.state(playerId).map(CombatState::lastAttacker).orElse(null);

        CombatConfig.CombatLog cl = config.combat().combatLog();
        if (!cl.enabled()) {
            // Punishment disabled: clear tag + cooldowns only.
            tagger.untag(playerId);
            plugin.messageService().clearCooldowns(playerId);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> processed.remove(playerId), 100L);
            return;
        }

        Location at = player.getLocation();
        World world = at.getWorld();
        PlayerInventory inv = player.getInventory();

        if (cl.dropInventory() && world != null) {
            dropContents(world, at, inv.getContents());
            dropContents(world, at, inv.getArmorContents());
            ItemStack offhand = inv.getItemInOffHand();
            if (offhand != null && !offhand.getType().isAir()) {
                world.dropItemNaturally(at, offhand.clone());
            }
            inv.clear();
            inv.setHelmet(null);
            inv.setChestplate(null);
            inv.setLeggings(null);
            inv.setBoots(null);
            inv.setItemInOffHand(null);
        }

        if (cl.dropExp() && world != null) {
            int totalExp = player.getTotalExperience();
            if (totalExp > 0) {
                ExperienceOrb orb = world.spawn(at, ExperienceOrb.class);
                orb.setExperience(totalExp);
            }
            player.setTotalExperience(0);
            player.setLevel(0);
            player.setExp(0f);
        }

        // Treat as a death: count it, credit the killer, queue a spawn respawn.
        creditDeathAndKill(player, killerId);
        pendingRespawn.add(playerId);

        tagger.untag(playerId);
        plugin.messageService().clearCooldowns(playerId);

        if (!cl.broadcast().isEmpty()) {
            MessageService messages = plugin.messageService();
            String rendered = LegacyFormat.replace(cl.broadcast(), "<player>", player.getName());
            messages.broadcast(rendered);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> processed.remove(playerId), 100L);
    }

    /**
     * On the combat-logger's next join, respawn them at the world spawn with
     * full health/food instead of at the logout location.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!pendingRespawn.remove(player.getUniqueId())) {
            return;
        }
        World world = player.getWorld();
        Location spawn = world != null ? world.getSpawnLocation() : null;
        if (spawn != null) {
            player.teleport(spawn);
        }
        try {
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
        } catch (Exception ignored) {
            // Health/food restore is best-effort; never block the join.
        }
        plugin.debugLog("Combat-logged player " + player.getName() + " respawned at spawn.");
    }

    private void creditDeathAndKill(Player quitter, UUID killerId) {
        try {
            quitter.incrementStatistic(Statistic.DEATHS);
        } catch (Exception ignored) {
            // Statistics may be unavailable in some contexts; never block the quit.
        }
        if (killerId == null) {
            return;
        }
        Player killer = Bukkit.getPlayer(killerId);
        if (killer != null && killer.isOnline()) {
            try {
                killer.incrementStatistic(Statistic.PLAYER_KILLS);
            } catch (Exception ignored) {
            }
        }
    }

    private void dropContents(World world, Location at, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            world.dropItemNaturally(at, item.clone());
        }
    }
}
