package hex.core.command.region;

import hex.core.api.region.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class Selection {
    private BlockPos pos1;
    private BlockPos pos2;
    private String world;
    private BukkitTask previewTask;

    public BlockPos pos1() { return pos1; }
    public BlockPos pos2() { return pos2; }
    public String world() { return world; }

    public void setPos1(String world, BlockPos pos) {
        this.world = world;
        this.pos1 = pos;
    }

    public void setPos2(String world, BlockPos pos) {
        this.world = world;
        this.pos2 = pos;
    }

    public boolean isComplete() {
        return world != null && pos1 != null && pos2 != null;
    }

    /** Clears pos1/pos2 and stops preview. */
    public void clear() {
        this.pos1 = null;
        this.pos2 = null;
        this.world = null;
        stopPreview();
    }

    /** Starts (or restarts) particle preview for this selection. */
    public void startPreview(Plugin plugin, Player player, Runnable drawRunnable, long periodTicks) {
        stopPreview();
        this.previewTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) { stopPreview(); return; }
            drawRunnable.run();
        }, 0L, periodTicks);
    }

    /** Stops preview task if running. */
    public void stopPreview() {
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
    }
}