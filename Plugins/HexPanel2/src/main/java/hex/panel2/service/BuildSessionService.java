package hex.panel2.service;

import hex.panel2.util.AccessControl;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class BuildSessionService {

    private final Plugin plugin;
    private final BossBar bossBar;
    private BukkitTask countdownTask;
    private long endAtMillis = 0L;
    private long totalDurationMillis = 1L;
    private boolean buildActive = false;

    public BuildSessionService(Plugin plugin) {
        this.plugin = plugin;
        this.bossBar = Bukkit.createBossBar("§eBudowanie nieaktywne", BarColor.YELLOW, BarStyle.SOLID);
        this.bossBar.setVisible(false);
    }

    public synchronized boolean isBuildActive() {
        return buildActive;
    }

    public synchronized void start(int minutes) {
        int normalizedMinutes = Math.max(1, minutes);
        long now = System.currentTimeMillis();
        this.totalDurationMillis = normalizedMinutes * 60_000L;
        this.endAtMillis = now + totalDurationMillis;
        this.buildActive = true;

        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }

        bossBar.setVisible(true);
        refreshBossBar(now);

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long current = System.currentTimeMillis();
            synchronized (BuildSessionService.this) {
                if (!buildActive) {
                    return;
                }

                if (current >= endAtMillis) {
                    endInternal();
                    Bukkit.broadcastMessage("§cCzas budowania skonczyl sie.");
                    return;
                }

                refreshBossBar(current);
            }
        }, 20L, 20L);
    }

    public synchronized void shutdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        bossBar.removeAll();
        bossBar.setVisible(false);
        buildActive = false;
    }

    private void endInternal() {
        buildActive = false;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        bossBar.setTitle("§cCzas budowania skonczyl sie");
        bossBar.setProgress(0.0);
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    private void refreshBossBar(long now) {
        long remaining = Math.max(0L, endAtMillis - now);
        long totalSeconds = remaining / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        double progress = Math.max(0.0, Math.min(1.0, remaining / (double) totalDurationMillis));

        bossBar.setTitle(String.format("§aCzas budowania: %02d:%02d", minutes, seconds));
        bossBar.setProgress(progress);
        bossBar.removeAll();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!AccessControl.isBypass(player)) {
                bossBar.addPlayer(player);
            }
        }
    }
}
