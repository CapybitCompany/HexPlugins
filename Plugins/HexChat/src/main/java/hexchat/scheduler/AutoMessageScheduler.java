package hexchat.scheduler;

import hexchat.config.HexChatConfig;
import hexchat.service.HexChatMessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutoMessageScheduler {

    private final JavaPlugin plugin;
    private final HexChatMessageService messageService;
    private volatile HexChatConfig.AutoMessages currentConfig;
    private volatile BukkitTask task;
    private final AtomicInteger sequentialIndex = new AtomicInteger(0);

    public AutoMessageScheduler(JavaPlugin plugin, HexChatMessageService messageService, HexChatConfig initialConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.currentConfig = Objects.requireNonNull(initialConfig, "initialConfig").autoMessages();
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        this.currentConfig = Objects.requireNonNull(updatedConfig, "updatedConfig").autoMessages();
        reschedule();
    }

    public void start() {
        reschedule();
    }

    public void stop() {
        BukkitTask runningTask = this.task;
        if (runningTask != null) {
            runningTask.cancel();
            this.task = null;
        }
    }

    private void reschedule() {
        stop();
        sequentialIndex.set(0);

        HexChatConfig.AutoMessages config = currentConfig;
        if (!config.enabled()) {
            return;
        }

        if (config.messages().isEmpty()) {
            plugin.getLogger().warning("auto-messages.enabled=true, ale lista wiadomości jest pusta.");
            return;
        }

        long intervalTicks = Math.max(20L, config.intervalSeconds() * 20L);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastNextMessage, intervalTicks, intervalTicks);
    }

    private void broadcastNextMessage() {
        HexChatConfig.AutoMessages config = currentConfig;
        if (!config.enabled()) {
            return;
        }

        List<String> messages = config.messages();
        if (messages.isEmpty()) {
            return;
        }

        String selected;
        if (config.randomOrder()) {
            int randomIndex = ThreadLocalRandom.current().nextInt(messages.size());
            selected = messages.get(randomIndex);
        } else {
            int index = Math.floorMod(sequentialIndex.getAndIncrement(), messages.size());
            selected = messages.get(index);
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            messageService.sendRawWithoutPrefix(onlinePlayer, selected, "auto-messages.messages");
        }
    }
}
