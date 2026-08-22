package hex.vishopbroadcast.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import hex.vishopbroadcast.proxy.command.VishopProxyCommand;
import hex.vishopbroadcast.proxy.config.ProxyConfigLoader;
import hex.vishopbroadcast.proxy.config.ProxySettings;
import hex.vishopbroadcast.proxy.database.ProxyDatabase;
import hex.vishopbroadcast.proxy.database.ProxyPurchaseRepository;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(
        id = "vishopbroadcastproxy",
        name = "VishopBroadcastProxy",
        version = "1.0.0",
        description = "Single Velocity writer for VishopBroadcast purchases",
        authors = {"HexTeam"}
)
public final class VishopBroadcastProxyPlugin {
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile ProxySettings settings;
    private volatile ProxyDatabase database;
    private volatile ProxyPurchaseRepository repository;
    private volatile ScheduledTask cleanupTask;

    @Inject
    public VishopBroadcastProxyPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxyServer.getCommandManager().register(
                proxyServer.getCommandManager().metaBuilder("vishopbroadcast").plugin(this).build(),
                new VishopProxyCommand(this, proxyServer)
        );
        if (!reloadConfiguration()) {
            logger.error("VishopBroadcastProxy could not load its configuration.");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ScheduledTask task = cleanupTask;
        if (task != null) {
            task.cancel();
        }
        ProxyDatabase activeDatabase = database;
        if (activeDatabase != null) {
            activeDatabase.close();
        }
    }

    public boolean reloadConfiguration() {
        final ProxySettings loaded;
        try {
            loaded = ProxyConfigLoader.load(dataDirectory);
        } catch (Exception exception) {
            logger.error("Could not load VishopBroadcastProxy config.yml", exception);
            return false;
        }
        if (this.settings == null) {
            this.settings = loaded;
        }
        connect(loaded);
        return true;
    }

    public ProxySettings settings() {
        return settings;
    }

    public ProxyPurchaseRepository repository() {
        return repository;
    }

    public boolean ready() {
        return repository != null;
    }

    public void executeDatabase(Runnable action) {
        proxyServer.getScheduler().buildTask(this, action).schedule();
    }

    public void logDatabaseError(String message, Throwable exception) {
        logger.error(message, exception);
    }

    private void connect(ProxySettings loaded) {
        if (!connecting.compareAndSet(false, true)) {
            logger.warn("A database reconnect is already running; the newest config will be used on the next reload.");
            return;
        }
        executeDatabase(() -> {
            ProxyDatabase nextDatabase = null;
            try {
                nextDatabase = new ProxyDatabase(loaded.database());
                ProxyPurchaseRepository nextRepository = new ProxyPurchaseRepository(nextDatabase.dataSource(), loaded);
                nextRepository.ensureTables();

                ProxyDatabase oldDatabase = this.database;
                this.database = nextDatabase;
                this.repository = nextRepository;
                this.settings = loaded;
                scheduleCleanup(loaded);
                if (oldDatabase != null) {
                    oldDatabase.close();
                }
                logger.info("VishopBroadcastProxy connected. This proxy is the only purchase writer.");
            } catch (Exception exception) {
                if (nextDatabase != null) {
                    nextDatabase.close();
                }
                logger.error("Could not initialize VishopBroadcastProxy database", exception);
            } finally {
                connecting.set(false);
            }
        });
    }

    private void scheduleCleanup(ProxySettings activeSettings) {
        ScheduledTask oldTask = cleanupTask;
        if (oldTask != null) {
            oldTask.cancel();
            cleanupTask = null;
        }
        if (!activeSettings.cleanup().enabled()) {
            return;
        }
        Duration delay = Duration.ofSeconds(secondsUntil(activeSettings.cleanup().hour(), activeSettings.cleanup().minute()));
        cleanupTask = proxyServer.getScheduler().buildTask(this, () -> {
                    ProxyPurchaseRepository activeRepository = repository;
                    if (activeRepository == null) {
                        return;
                    }
                    try {
                        int deleted = activeRepository.cleanupOlderThanDays(settings.cleanup().retentionDays());
                        if (deleted > 0) {
                            logger.info("Deleted {} old vishop purchase logs.", deleted);
                        }
                    } catch (Exception exception) {
                        logger.warn("Could not cleanup old vishop purchase logs", exception);
                    }
                })
                .delay(delay)
                .repeat(Duration.ofDays(1))
                .schedule();
    }

    private static long secondsUntil(int hour, int minute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.with(LocalTime.of(hour, minute)).truncatedTo(ChronoUnit.MINUTES);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return Math.max(1L, Duration.between(now, target).toSeconds());
    }
}
