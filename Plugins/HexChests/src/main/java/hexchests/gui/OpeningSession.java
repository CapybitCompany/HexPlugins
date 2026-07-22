package hexchests.gui;

import hexchests.config.HexChestsConfig;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class OpeningSession {

    private final UUID playerId;
    private final String chestId;
    private final HexChestsConfig.RewardDefinition reward;
    private final Inventory inventory;
    private BukkitTask task;
    private boolean finished;

    public OpeningSession(UUID playerId,
                          String chestId,
                          HexChestsConfig.RewardDefinition reward,
                          Inventory inventory) {
        this.playerId = playerId;
        this.chestId = chestId;
        this.reward = reward;
        this.inventory = inventory;
    }

    public UUID playerId() {
        return playerId;
    }

    public String chestId() {
        return chestId;
    }

    public HexChestsConfig.RewardDefinition reward() {
        return reward;
    }

    public Inventory inventory() {
        return inventory;
    }

    public BukkitTask task() {
        return task;
    }

    public void task(BukkitTask task) {
        this.task = task;
    }

    public boolean finished() {
        return finished;
    }

    public void finished(boolean finished) {
        this.finished = finished;
    }
}
