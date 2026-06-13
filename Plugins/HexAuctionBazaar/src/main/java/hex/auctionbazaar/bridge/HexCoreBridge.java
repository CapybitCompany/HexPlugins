package hex.auctionbazaar.bridge;

import hex.core.api.HexApi;
import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Resolves HexCore via the Bukkit ServicesManager. HexCore is a hard depend
 * in plugin.yml, so by onEnable time the service should exist; if not we
 * fail-fast in tryBootstrap.
 */
public final class HexCoreBridge {

    private final Logger logger;
    private HexApi hexApi;

    public HexCoreBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean tryBootstrap() {
        RegisteredServiceProvider<HexApi> reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg == null) {
            logger.severe("HexCore HexApi not registered in ServicesManager.");
            return false;
        }
        this.hexApi = reg.getProvider();
        return hexApi != null;
    }

    public boolean isAvailable() {
        return hexApi != null;
    }

    public HexApi api() {
        return hexApi;
    }

    public DatabaseService db() {
        return hexApi.db();
    }

    public Db rawDb() {
        return hexApi.db().db();
    }

    public <T> CompletableFuture<T> async(Supplier<T> work) {
        return hexApi.db().async(work);
    }

    public CompletableFuture<Void> asyncRun(Runnable work) {
        return hexApi.db().asyncRun(work);
    }
}
