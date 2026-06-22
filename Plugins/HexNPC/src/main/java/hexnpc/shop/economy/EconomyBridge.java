package hexnpc.shop.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Bridge do hex.economy.api.HexEconomyApi w 100% przez reflection.
 * HexNPC nie ma zależności compile-time od HexEconomy — klasę API
 * rozwiązujemy leniwie przez ClassLoader pluginu HexEconomy, a
 * referencję serwisu pobieramy z Bukkit ServicesManager. Zreflektowane
 * metody są cache'owane.
 *
 * Operacje withdraw/deposit działają na dedykowanym jednowątkowym
 * executorze, więc główny wątek serwera nie blokuje się na backendzie
 * ekonomii. Caller wraca na główny wątek samodzielnie (scheduler).
 */
public class EconomyBridge {

    private static final String PLUGIN_NAME = "HexEconomy";
    private static final String API_CLASS = "hex.economy.api.HexEconomyApi";

    private final Logger logger;
    private final ExecutorService executor;

    private volatile Plugin cachedPlugin;
    private volatile Class<?> apiClass;
    private volatile Object apiInstance;
    private volatile Method deposit;
    private volatile Method withdraw;
    private volatile Method format;
    private volatile Method currencyName;

    private volatile Method resultSuccess;
    private volatile Method resultBalance;
    private volatile Method resultReason;

    private final AtomicBoolean loggedAvailable = new AtomicBoolean(false);
    private final AtomicBoolean loggedMissing = new AtomicBoolean(false);

    public EconomyBridge(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(new EconomyThreadFactory());
    }

    public boolean isAvailable() {
        return resolveApi() != null;
    }

    public String currencyName() {
        Object api = resolveApi();
        if (api == null || currencyName == null) {
            return "$";
        }
        try {
            Object out = currencyName.invoke(api);
            return out == null ? "$" : out.toString();
        } catch (Throwable t) {
            return "$";
        }
    }

    public String format(BigDecimal value) {
        Object api = resolveApi();
        if (api == null || format == null || value == null) {
            return value == null ? "0" : value.toPlainString();
        }
        try {
            Object out = format.invoke(api, value);
            return out == null ? value.toPlainString() : out.toString();
        } catch (Throwable t) {
            return value.toPlainString();
        }
    }

    public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName, BigDecimal amount, String reason) {
        return invokeAsync(withdraw, uuid, playerName, amount, reason, "withdraw");
    }

    public CompletableFuture<TxResult> deposit(UUID uuid, String playerName, BigDecimal amount, String reason) {
        return invokeAsync(deposit, uuid, playerName, amount, reason, "deposit");
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<TxResult> invokeAsync(Method method, UUID uuid, String playerName,
                                                    BigDecimal amount, String reason, String label) {
        Object api = resolveApi();
        if (api == null || method == null) {
            return CompletableFuture.completedFuture(TxResult.fail("economy-unavailable"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object raw = method.invoke(api, uuid, playerName, amount, reason);
                return adaptResult(raw);
            } catch (Throwable t) {
                Throwable root = t.getCause() != null ? t.getCause() : t;
                if (logger != null) {
                    logger.fine("HexNPC: economy " + label + " failed: " + root.getMessage());
                }
                return TxResult.fail(root.getMessage() == null ? "error" : root.getMessage());
            }
        }, executor);
    }

    private TxResult adaptResult(Object raw) {
        if (raw == null) {
            return TxResult.fail("null-result");
        }
        try {
            ensureResultAccessors(raw.getClass());
            boolean success = Boolean.TRUE.equals(resultSuccess.invoke(raw));
            Object bal = resultBalance.invoke(raw);
            Object reason = resultReason.invoke(raw);
            BigDecimal balance = bal instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
            String reasonStr = reason == null ? "" : reason.toString();
            return new TxResult(success, balance, reasonStr);
        } catch (Throwable t) {
            return TxResult.fail(t.getMessage() == null ? "result-error" : t.getMessage());
        }
    }

    private void ensureResultAccessors(Class<?> resultType) throws NoSuchMethodException {
        if (resultSuccess != null && resultBalance != null && resultReason != null) {
            return;
        }
        Method s = resultType.getMethod("success");
        Method b = resultType.getMethod("balance");
        Method r = resultType.getMethod("reason");
        s.setAccessible(true);
        b.setAccessible(true);
        r.setAccessible(true);
        this.resultSuccess = s;
        this.resultBalance = b;
        this.resultReason = r;
    }

    private Object resolveApi() {
        Plugin plugin = lookupEconomyPlugin();
        if (plugin == null) {
            invalidate();
            return null;
        }
        // Jeśli mamy już cache pod tę instancję pluginu i serwis dalej
        // jest zarejestrowany — fast path.
        Object instance = apiInstance;
        if (instance != null && plugin == cachedPlugin && stillRegistered(instance)) {
            return instance;
        }
        // Klasę API ładujemy przez ClassLoader pluginu HexEconomy.
        // Wspólny parent ClassLoader nie widzi pakietów plugin-private.
        Class<?> type;
        try {
            type = Class.forName(API_CLASS, true, plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException ex) {
            if (loggedMissing.compareAndSet(false, true)) {
                log("HexNPC: HexEconomy is loaded but " + API_CLASS + " is not visible from its classloader.");
            }
            invalidate();
            return null;
        }
        ServicesManager sm;
        try {
            sm = Bukkit.getServicesManager();
        } catch (Throwable t) {
            return null;
        }
        if (sm == null) {
            return null;
        }
        @SuppressWarnings("rawtypes")
        RegisteredServiceProvider rsp = sm.getRegistration((Class) type);
        if (rsp == null) {
            // Plugin załadowany, ale serwis jeszcze (lub już) nie jest
            // zarejestrowany. Nie spamujemy logów.
            apiInstance = null;
            apiClass = type;
            cachedPlugin = plugin;
            return null;
        }
        Object provider = rsp.getProvider();
        if (provider == null) {
            apiInstance = null;
            return null;
        }
        try {
            this.deposit = type.getMethod("deposit", UUID.class, String.class, BigDecimal.class, String.class);
            this.withdraw = type.getMethod("withdraw", UUID.class, String.class, BigDecimal.class, String.class);
            this.format = type.getMethod("format", BigDecimal.class);
            this.currencyName = type.getMethod("currencyName");
        } catch (NoSuchMethodException ex) {
            log("HexNPC: HexEconomy API surface mismatch: " + ex.getMessage());
            invalidate();
            return null;
        }
        this.apiClass = type;
        this.cachedPlugin = plugin;
        this.apiInstance = provider;
        if (loggedAvailable.compareAndSet(false, true)) {
            log("HexNPC: HexEconomy bridge ready (currency=" + safeCurrency() + ").");
        }
        loggedMissing.set(false);
        return provider;
    }

    private Plugin lookupEconomyPlugin() {
        PluginManager pm;
        try {
            pm = Bukkit.getPluginManager();
        } catch (Throwable t) {
            return null;
        }
        if (pm == null) {
            return null;
        }
        Plugin plugin = pm.getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        return plugin;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean stillRegistered(Object instance) {
        try {
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration((Class) apiClass);
            return rsp != null && rsp.getProvider() == instance;
        } catch (Throwable t) {
            return false;
        }
    }

    private void invalidate() {
        apiInstance = null;
        cachedPlugin = null;
    }

    private void log(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }

    private String safeCurrency() {
        try {
            return currencyName == null || apiInstance == null
                    ? "?"
                    : String.valueOf(currencyName.invoke(apiInstance));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static final class EconomyThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "HexNPC-EconomyBridge-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
