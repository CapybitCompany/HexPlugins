package hex.minigames.runtime;
import hex.minigames.config.Messages;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import java.util.*;
/** Winner announcement and harmless fireworks around the waiting-room spawn. */
public final class SeriesCelebration implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Set<Firework> fireworks = new HashSet<>();
    public SeriesCelebration(Plugin plugin) { this.plugin = plugin; }
    public void show(Collection<Player> players, Location lobby, String winner, Messages messages) {
        show(players, lobby, List.of(winner), messages);
    }
    public void show(Collection<Player> players, Location lobby, List<String> winners, Messages messages) {
        for (Player player : players) {
            Text.showTitle(player, winners.size() > 1 ? messages.raw("series-tie-title", "&6Remis!") : messages.raw("series-winner-title", "&2&lZWYCIĘZCA"),
                    messages.raw("series-winner-subtitle", "&6{player}").replace("{player}", String.join(", ", winners)), 10, 100, 20);
            player.playSound(player.getLocation(), messages.raw("series-winner-sound", "minecraft:ui.toast.challenge_complete"), 1, 1);
        }
        if (lobby == null || lobby.getWorld() == null) return;
        if (!Boolean.parseBoolean(messages.raw("series-fireworks-enabled", "true"))) return;
        for (int i = 0; i < 5; i++) {
            double angle = i * Math.PI * 2 / 5;
            Firework firework = lobby.getWorld().spawn(lobby.clone().add(Math.cos(angle)*3, 2, Math.sin(angle)*3), Firework.class, entity -> {
                entity.setPersistent(false);
                var meta = entity.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.LIME, Color.YELLOW).withFlicker().build());
                meta.setPower(0); entity.setFireworkMeta(meta);
            });
            fireworks.add(firework);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (firework.isValid()) firework.detonate(); }, 2 + i * 8);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { firework.remove(); fireworks.remove(firework); }, 60);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework && fireworks.contains(firework)) event.setCancelled(true);
    }
    @Override public void close() { fireworks.forEach(Entity::remove); fireworks.clear(); }
}
