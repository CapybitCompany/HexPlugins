package mysterybox.service;

import mysterybox.config.MysteryBoxConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class AuditLogService {

    private final JavaPlugin plugin;
    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ExecutorService writerExecutor;

    public AuditLogService(JavaPlugin plugin, Supplier<MysteryBoxConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mysterybox-audit-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void logOpeningResult(Player player, String openingId, MysteryBoxConfig.RewardSettings reward) {
        MysteryBoxConfig.AuditSettings audit = configSupplier.get().audit();
        if (!audit.enabled()) {
            return;
        }

        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();
        String world = player.getWorld().getName();
        Location location = player.getLocation();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        String rewardId = reward.id();
        String rewardName = reward.winMessage();
        int rewardChance = reward.chance();
        String timestamp = Instant.now().toString();
        String fileName = audit.fileName();

        writerExecutor.execute(() -> writeOpeningLog(
                timestamp,
                openingId,
                playerName,
                playerUuid,
                rewardId,
                rewardName,
                rewardChance,
                world,
                x,
                y,
                z,
                fileName
        ));
    }

    public void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }

    private void writeOpeningLog(
            String timestamp,
            String openingId,
            String playerName,
            String playerUuid,
            String rewardId,
            String rewardName,
            int rewardChance,
            String world,
            double x,
            double y,
            double z,
            String fileName
    ) {
        Path logFile = plugin.getDataFolder().toPath().resolve("logs").resolve(fileName);
        try {
            Files.createDirectories(logFile.getParent());
            String line = buildJsonLine(
                    timestamp,
                    openingId,
                    playerName,
                    playerUuid,
                    rewardId,
                    rewardName,
                    rewardChance,
                    world,
                    x,
                    y,
                    z
            );
            Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            plugin.getLogger().warning("Nie udało się zapisać wpisu audytu MysteryBox: " + ex.getMessage());
        }
    }

    private String buildJsonLine(
            String timestamp,
            String openingId,
            String playerName,
            String playerUuid,
            String rewardId,
            String rewardName,
            int rewardChance,
            String world,
            double x,
            double y,
            double z
    ) {
        return "{"
                + "\"event\":\"opening_result\","
                + "\"timestamp\":\"" + escapeJson(timestamp) + "\","
                + "\"opening_id\":\"" + escapeJson(openingId) + "\","
                + "\"player_name\":\"" + escapeJson(playerName) + "\","
                + "\"player_uuid\":\"" + escapeJson(playerUuid) + "\","
                + "\"reward_id\":\"" + escapeJson(rewardId) + "\","
                + "\"reward_name\":\"" + escapeJson(rewardName) + "\","
                + "\"reward_chance\":" + rewardChance + ","
                + "\"world\":\"" + escapeJson(world) + "\","
                + "\"x\":" + String.format(Locale.ROOT, "%.3f", x) + ","
                + "\"y\":" + String.format(Locale.ROOT, "%.3f", y) + ","
                + "\"z\":" + String.format(Locale.ROOT, "%.3f", z)
                + "}";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
