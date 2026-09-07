package hex.parkour.service;

import hex.parkour.config.BarConfig;
import hex.parkour.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BossBarService {
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public void show(Player player, BarConfig config) {
        hide(player.getUniqueId());
        BossBar bar = Bukkit.createBossBar(Text.color(config.title()), config.color(), config.style());
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bars.put(player.getUniqueId(), bar);
    }

    public void hide(UUID playerId) {
        BossBar previous = bars.remove(playerId);
        if (previous != null) previous.removeAll();
    }

    public void hideAll() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
    }
}
