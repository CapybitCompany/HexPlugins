package hex.auctionbazaar.bridge;

import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Wrapper around HexEconomyApi. Every call runs on a dedicated single-thread
 * executor so the Bukkit main thread is not blocked by wallet DB calls.
 * HexEconomy persists asynchronously through HexCore DB, but the API itself
 * would block the calling thread if invoked from the main thread.
 */
public final class EconomyBridge {

    private final Logger logger;
    private final ExecutorService executor;
    private HexEconomyApi economy;

    public EconomyBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Executors.newSingleThreadExecutor(named("hex-auction-bazaar-economy"));
    }

    public boolean tryBootstrap() {
        RegisteredServiceProvider<HexEconomyApi> reg = Bukkit.getServicesManager().getRegistration(HexEconomyApi.class);
        if (reg == null) {
            logger.warning("HexEconomyApi is not registered - buy/sell is not possible.");
            return false;
        }
        this.economy = reg.getProvider();
        return economy != null;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public HexEconomyApi api() {
        return economy;
    }

    public String currency() {
        return economy == null ? "?" : economy.currencyName();
    }

    public String format(BigDecimal value) {
        return economy == null ? String.valueOf(value) : economy.format(value);
    }

    public CompletableFuture<BigDecimal> getBalance(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> economy.getBalance(uuid), executor);
    }

    public CompletableFuture<EconomyResult> deposit(UUID uuid, String name, BigDecimal amount, String reason) {
        return CompletableFuture.supplyAsync(() -> economy.deposit(uuid, name, amount, reason), executor);
    }

    public CompletableFuture<EconomyResult> withdraw(UUID uuid, String name, BigDecimal amount, String reason) {
        return CompletableFuture.supplyAsync(() -> economy.withdraw(uuid, name, amount, reason), executor);
    }

    public CompletableFuture<Boolean> has(UUID uuid, BigDecimal amount) {
        return CompletableFuture.supplyAsync(() -> economy.has(uuid, amount), executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    private static ThreadFactory named(String base) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, base + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
