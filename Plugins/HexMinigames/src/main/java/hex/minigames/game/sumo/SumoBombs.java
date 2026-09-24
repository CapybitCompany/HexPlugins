package hex.minigames.game.sumo;

import hex.minigames.game.RoundContext;
import hex.minigames.game.common.GameSettings;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;

/** Round-owned falling pickups and thrown charges with harmless, inclusive knockback. */
final class SumoBombs {
    private final NamespacedKey key;
    private final Random random = new Random();
    private final Map<Item, Long> pickups = new HashMap<>();
    private final Map<Item, Long> thrown = new HashMap<>();
    private long nextSpawn;

    SumoBombs(Plugin plugin) { key = new NamespacedKey(plugin, "sumo_bomb"); }

    void start(RoundContext context) { schedule(context); }

    private void schedule(RoundContext context) {
        Object settings = context.definition().settings();
        int min = Math.clamp(GameSettings.integer(settings, "bombs.min-interval-seconds", 8), 1, 3600);
        int max = Math.clamp(GameSettings.integer(settings, "bombs.max-interval-seconds", 16), min, 3600);
        nextSpawn = context.elapsedTicks() + random.nextInt(min, max + 1) * 20L;
    }

    void tick(RoundContext context) {
        if (GameSettings.bool(context.definition().settings(), "bombs.enabled", true) && context.elapsedTicks() >= nextSpawn) {
            List<Player> players = context.onlineParticipants().stream()
                    .filter(p -> context.state(p.getUniqueId()) == RoundPlayerState.ACTIVE)
                    .filter(p -> SumoRuntime.onArena(p.getX(), p.getY(), p.getZ())).toList();
            if (!players.isEmpty()) {
                double angle = random.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(random.nextDouble()) * 7;
                double height = Math.clamp(GameSettings.decimal(context.definition().settings(), "bombs.spawn-height", 6), 1, 20);
                Location at = new Location(players.getFirst().getWorld(), 438.5 + Math.cos(angle) * radius,
                        players.stream().mapToDouble(Player::getY).max().orElse(48) + 1.8 + height,
                        349.5 + Math.sin(angle) * radius);
                Item item = drop(at, charge());
                item.setVelocity(new Vector());
                pickups.put(item, context.elapsedTicks() + 400);
                for (Player player : context.onlineParticipants())
                    player.playSound(player.getLocation(), "minecraft:entity.glow_squid.ambient", 1, 1);
            }
            schedule(context);
        }
        for (var entry : List.copyOf(pickups.entrySet())) {
            Item item = entry.getKey();
            if (!item.isValid() || context.elapsedTicks() >= entry.getValue() || item.getY() < 40) {
                item.remove(); pickups.remove(item); continue;
            }
            for (Player player : context.onlineParticipants()) {
                if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE || !player.getWorld().equals(item.getWorld())
                        || player.getLocation().distanceSquared(item.getLocation()) > 2.25) continue;
                if (player.getInventory().firstEmpty() < 0) continue;
                player.getInventory().addItem(charge());
                item.remove(); pickups.remove(item); break;
            }
        }
        for (var entry : List.copyOf(thrown.entrySet())) {
            Item item = entry.getKey();
            if (!item.isValid()) { thrown.remove(item); continue; }
            if (item.isOnGround() || context.elapsedTicks() >= entry.getValue()) {
                explode(context, item.getLocation()); item.remove(); thrown.remove(item);
            }
        }
    }

    boolean interact(RoundContext context, PlayerInteractEvent event) {
        if (event.getHand() == null || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !isCharge(event.getItem()) || context.state(event.getPlayer().getUniqueId()) != RoundPlayerState.ACTIVE) return false;
        Player player = event.getPlayer();
        Item item = drop(player.getEyeLocation(), charge());
        item.setVelocity(player.getEyeLocation().getDirection().multiply(1.2));
        thrown.put(item, context.elapsedTicks() + 25);
        ItemStack held = player.getInventory().getItem(event.getHand());
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(event.getHand(), held);
        return true;
    }

    private Item drop(Location location, ItemStack stack) {
        return location.getWorld().dropItem(location, stack, item -> {
            item.setPersistent(false); item.setInvulnerable(true);
            item.setCanPlayerPickup(false); item.setCanMobPickup(false);
            item.setUnlimitedLifetime(true);
            item.setGlowing(true);
        });
    }

    private ItemStack charge() {
        ItemStack item = new ItemStack(Material.FIRE_CHARGE);
        var meta = item.getItemMeta();
        meta.displayName(hex.minigames.util.Text.component("&6Bomba &7(PPM)"));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCharge(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void explode(RoundContext context, Location at) {
        double radius = Math.clamp(GameSettings.decimal(context.definition().settings(), "bombs.radius", 7), 1, 20);
        double strength = Math.clamp(GameSettings.decimal(context.definition().settings(), "bombs.knockback", 1.8), 0.1, 5);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
        at.getWorld().playSound(at, "minecraft:entity.generic.explode", 1, 1);
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE || !player.getWorld().equals(at.getWorld())
                    || player.getLocation().distanceSquared(at) > radius * radius) continue;
            Vector away = player.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            player.setVelocity(away.normalize().multiply(strength).setY(0.55));
        }
    }

    void clear(RoundContext context) {
        pickups.keySet().forEach(Item::remove); thrown.keySet().forEach(Item::remove);
        pickups.clear(); thrown.clear();
        for (Player player : context.onlineParticipants()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++)
                if (isCharge(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
    }
}
