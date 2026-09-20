package hex.minigames.game.common;

import hex.minigames.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarDisplay {
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public void show(Player player, BossBarSettings settings) {
        if (player == null || settings == null) return;
        remove(player.getUniqueId());
        BossBar bar = Bukkit.createBossBar(Text.color(settings.title()), settings.color(), settings.style());
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bars.put(player.getUniqueId(), bar);
    }

    public void showAll(Iterable<Player> players, BossBarSettings settings) {
        for (Player player : players) show(player, settings);
    }

    public void remove(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) bar.removeAll();
    }

    public void clear() {
        for (UUID playerId : List.copyOf(bars.keySet())) remove(playerId);
    }
}
