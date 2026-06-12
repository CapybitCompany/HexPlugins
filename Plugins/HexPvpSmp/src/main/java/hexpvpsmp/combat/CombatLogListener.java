package hexpvpsmp.combat;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.CombatConfig;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.ui.MessageService;
import hexpvpsmp.util.LegacyFormat;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Detects combat-log: quit while tagged. Manually drops inventory + armor +
 * offhand + (optionally) experience at the quit location, clears the player's
 * stash, clears the combat tag, and broadcasts. Idempotent against
 * duplicate quit events.
 */
public final class CombatLogListener implements Listener {

    private final HexPvpSmpPlugin plugin;
    private final Set<UUID> processed = new HashSet<>();

    public CombatLogListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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

        tagger.untag(playerId);
        plugin.messageService().clearCooldowns(playerId);

        if (!cl.broadcast().isEmpty()) {
            MessageService messages = plugin.messageService();
            String rendered = LegacyFormat.replace(cl.broadcast(), "<player>", player.getName());
            messages.broadcast(rendered);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> processed.remove(playerId), 100L);
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
