package hex.vishopbroadcast.service;

import hex.core.api.HexApi;
import hex.vishopbroadcast.config.ConfiguredService;
import hex.vishopbroadcast.config.DisplayChannel;
import hex.vishopbroadcast.config.ServiceDisplay;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.database.PurchaseRepository;
import hex.vishopbroadcast.model.PurchaseRecord;
import hex.vishopbroadcast.text.PurchaseTextFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class PurchaseBroadcastService {
    private final Plugin plugin;
    private final HexApi api;
    private final PurchaseRepository repository;
    private final Supplier<VishopSettings> settingsSupplier;
    private final PurchaseTextFactory textFactory;
    private final Queue<PurchaseRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean displaying = new AtomicBoolean(false);
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private BukkitTask pollTask;
    private BukkitTask cleanupTask;
    private volatile long lastSeenId;

    public PurchaseBroadcastService(
            Plugin plugin,
            HexApi api,
            PurchaseRepository repository,
            Supplier<VishopSettings> settingsSupplier,
            PurchaseTextFactory textFactory
    ) {
        this.plugin = plugin;
        this.api = api;
        this.repository = repository;
        this.settingsSupplier = settingsSupplier;
        this.textFactory = textFactory;
    }

    public void start(long initialLastSeenId) {
        stop();
        this.lastSeenId = Math.max(0, initialLastSeenId);
        VishopSettings settings = settingsSupplier.get();
        long pollTicks = Math.max(20L, settings.pollIntervalSeconds() * 20L);
        this.pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, pollTicks, pollTicks);
        scheduleCleanup();
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        displaying.set(false);
        polling.set(false);
        queue.clear();
    }

    public void enqueue(List<PurchaseRecord> records) {
        if (records == null || records.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        queue.addAll(records);
        triggerDisplayLoop();
    }

    private void poll() {
        if (!plugin.isEnabled() || !polling.compareAndSet(false, true)) {
            return;
        }
        VishopSettings settings = settingsSupplier.get();
        long seenBeforeQuery = lastSeenId;
        api.db().async(() -> repository.findAfter(seenBeforeQuery, settings.fetchLimit()))
                .whenComplete((records, throwable) -> {
                    polling.set(false);
                    if (throwable != null) {
                        plugin.getLogger().warning("Could not poll vishop purchases: " + throwable.getMessage());
                        return;
                    }
                    if (records == null || records.isEmpty()) {
                        return;
                    }
                    lastSeenId = records.getLast().id();
                    Bukkit.getScheduler().runTask(plugin, () -> enqueue(records));
                });
    }

    private void triggerDisplayLoop() {
        if (displaying.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, this::displayNext);
        }
    }

    private void displayNext() {
        if (!plugin.isEnabled()) {
            displaying.set(false);
            return;
        }

        PurchaseRecord record = queue.poll();
        if (record == null) {
            displaying.set(false);
            if (!queue.isEmpty()) {
                triggerDisplayLoop();
            }
            return;
        }

        VishopSettings settings = settingsSupplier.get();
        ConfiguredService service = settings.serviceByKey(record.serviceKey()).orElse(null);
        int delaySeconds;
        if (service == null) {
            broadcastFallback(record);
            delaySeconds = settings.minChatOnlyIntervalSeconds();
        } else {
            broadcastConfigured(settings, service, record);
            ServiceDisplay display = service.display();
            delaySeconds = display.durationSeconds();
            if (display.chatOnly()) {
                delaySeconds = Math.max(delaySeconds, settings.minChatOnlyIntervalSeconds());
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::displayNext, Math.max(20L, delaySeconds * 20L));
    }

    private void broadcastConfigured(VishopSettings settings, ConfiguredService service, PurchaseRecord record) {
        ServiceDisplay display = service.display();
        Map<String, String> placeholders = textFactory.placeholdersForRecord(settings, service, record);

        Component actionbar = display.hasChannel(DisplayChannel.ACTION_BAR)
                ? textFactory.component(display.actionbar(), placeholders)
                : null;
        Component title = display.hasChannel(DisplayChannel.TITLE)
                ? textFactory.component(display.title(), placeholders)
                : null;
        Component subtitle = display.hasChannel(DisplayChannel.TITLE)
                ? textFactory.component(display.subtitle(), placeholders)
                : null;
        Title.Times titleTimes = Title.Times.times(
                ticks(display.titleFadeInTicks()),
                ticks(display.titleStayTicks()),
                ticks(display.titleFadeOutTicks())
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (display.hasChannel(DisplayChannel.CHAT)) {
                for (String line : display.chatLines()) {
                    player.sendMessage(textFactory.component(line, placeholders));
                }
            }
            if (actionbar != null) {
                player.sendActionBar(actionbar);
            }
            if (title != null && subtitle != null) {
                player.showTitle(Title.title(title, subtitle, titleTimes));
            }
        }
    }

    private void broadcastFallback(PurchaseRecord record) {
        Component component = Component.text(record.broadcastInfo() == null ? "Nowy zakup w vishop." : record.broadcastInfo());
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
    }

    private void scheduleCleanup() {
        VishopSettings settings = settingsSupplier.get();
        if (!settings.cleanupEnabled()) {
            return;
        }
        long delayTicks = ticksUntilCleanup(settings.cleanupHour(), settings.cleanupMinute()) * 20L;
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runCleanup, delayTicks, 24L * 60L * 60L * 20L);
    }

    private void runCleanup() {
        VishopSettings settings = settingsSupplier.get();
        api.db().async(() -> repository.cleanupOlderThanDays(settings.retentionDays()))
                .thenAccept(deleted -> {
                    if (deleted != null && deleted > 0) {
                        plugin.getLogger().info("Deleted " + deleted + " old vishop purchase logs.");
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Could not cleanup old vishop purchase logs: " + ex.getMessage());
                    return null;
                });
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0L, ticks) * 50L);
    }

    private static long ticksUntilCleanup(int hour, int minute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.with(LocalTime.of(hour, minute)).truncatedTo(ChronoUnit.MINUTES);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return Math.max(1L, Duration.between(now, target).toSeconds());
    }
}

