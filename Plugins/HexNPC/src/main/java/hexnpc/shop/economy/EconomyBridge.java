package hexnpc.shop.economy;

import hexnpc.shop.model.ShopCurrency;
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
 * Bridge do {@code hex.economy.api.HexEconomyApi} w 100% przez reflection.
 *
 * <p>Hotfix 1.1.1 rozdziela dwa tory API:
 * <ul>
 *   <li>{@link ShopCurrency#MONEY} zawsze korzysta wyłącznie z historycznego
 *       API HexEconomy używanego przez HexNPC 1.0.0,</li>
 *   <li>{@link ShopCurrency#HEX_COINS} dopiero na żądanie rozwiązuje opcjonalne
 *       API multi-currency z {@code CurrencyType}.</li>
 * </ul>
 * Awaria lub brak multi-currency nigdy nie może unieważnić legacy MONEY.
 *
 * <p>Operacje withdraw/deposit działają na dedykowanym jednowątkowym
 * executorze, więc główny wątek serwera nie blokuje się na backendzie ekonomii.
 */
public class EconomyBridge {

    private static final String PLUGIN_NAME = "HexEconomy";
    private static final String API_CLASS = "hex.economy.api.HexEconomyApi";
    private static final String CURRENCY_CLASS = "hex.economy.api.CurrencyType";

    private final Logger logger;
    private final ExecutorService executor;

    // Legacy API — jedyny tor używany przez MONEY.
    private volatile Plugin cachedPlugin;
    private volatile Class<?> apiClass;
    private volatile Object apiInstance;
    private volatile Method deposit;
    private volatile Method withdraw;
    private volatile Method format;
    private volatile Method currencyName;

    // Opcjonalne multi-currency API — rozwiązywane wyłącznie dla HEX_COINS.
    private volatile boolean multiResolved;
    private volatile Class<?> currencyClass;
    private volatile Method multiDeposit;
    private volatile Method multiWithdraw;
    private volatile Method multiFormat;
    private volatile Method multiCurrencyName;
    private volatile Method isCurrencyAvailable;

    private volatile Method resultSuccess;
    private volatile Method resultBalance;
    private volatile Method resultReason;

    private final AtomicBoolean loggedAvailable = new AtomicBoolean(false);
    private final AtomicBoolean loggedMissing = new AtomicBoolean(false);
    private final AtomicBoolean loggedMultiUnavailable = new AtomicBoolean(false);
    private final AtomicBoolean loggedMultiAvailable = new AtomicBoolean(false);

    public EconomyBridge(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(new EconomyThreadFactory());
    }

    /** Dostępność legacy ekonomii MONEY. Nie dotyka CurrencyType. */
    public boolean isAvailable() {
        return resolveLegacyApi() != null;
    }

    /** Sprawdza dostępność konkretnej waluty sklepu. */
    public boolean isAvailable(ShopCurrency currency) {
        ShopCurrency effective = currency == null ? ShopCurrency.MONEY : currency;
        if (effective == ShopCurrency.MONEY) {
            return isAvailable();
        }

        Object api = resolveMultiCurrencyApi();
        if (api == null) {
            return false;
        }
        Object currencyValue = resolveCurrencyValue(effective);
        if (currencyValue == null || multiWithdraw == null || multiDeposit == null) {
            return false;
        }
        Method availability = isCurrencyAvailable;
        if (availability == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(availability.invoke(api, currencyValue));
        } catch (Throwable t) {
            logFine("HexNPC: multi-currency availability check failed: " + rootMessage(t));
            return false;
        }
    }

    /** Nazwa legacy waluty MONEY. */
    public String currencyName() {
        Object api = resolveLegacyApi();
        Method method = currencyName;
        if (api == null || method == null) {
            return "$";
        }
        try {
            Object out = method.invoke(api);
            return out == null ? "$" : out.toString();
        } catch (Throwable t) {
            return "$";
        }
    }

    /** Nazwa waluty dla konkretnego sklepu. MONEY zawsze używa legacy API. */
    public String currencyName(ShopCurrency currency) {
        ShopCurrency effective = currency == null ? ShopCurrency.MONEY : currency;
        if (effective == ShopCurrency.MONEY) {
            return currencyName();
        }

        Object api = resolveMultiCurrencyApi();
        Object currencyValue = resolveCurrencyValue(effective);
        Method method = multiCurrencyName;
        if (api == null || currencyValue == null || method == null) {
            return "HexCoins";
        }
        try {
            Object out = method.invoke(api, currencyValue);
            return out == null ? "HexCoins" : out.toString();
        } catch (Throwable t) {
            return "HexCoins";
        }
    }

    /** Legacy format MONEY — dokładnie ścieżka z HexNPC 1.0.0. */
    public String format(BigDecimal value) {
        Object api = resolveLegacyApi();
        Method method = format;
        if (api == null || method == null || value == null) {
            return value == null ? "0" : value.toPlainString();
        }
        try {
            Object out = method.invoke(api, value);
            return out == null ? value.toPlainString() : out.toString();
        } catch (Throwable t) {
            return value.toPlainString();
        }
    }

    /** Formatuje wartość w walucie konkretnego sklepu. */
    public String format(ShopCurrency currency, BigDecimal value) {
        ShopCurrency effective = currency == null ? ShopCurrency.MONEY : currency;
        if (effective == ShopCurrency.MONEY) {
            return format(value);
        }

        if (value == null) {
            return "0 HexCoins";
        }
        Object api = resolveMultiCurrencyApi();
        Object currencyValue = resolveCurrencyValue(effective);
        Method method = multiFormat;
        if (api == null || currencyValue == null || method == null) {
            return fallbackHexCoins(value);
        }
        try {
            Object out = method.invoke(api, currencyValue, value);
            return out == null ? fallbackHexCoins(value) : out.toString();
        } catch (Throwable t) {
            return fallbackHexCoins(value);
        }
    }

    /** Legacy withdraw MONEY. */
    public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName, BigDecimal amount, String reason) {
        Object api = resolveLegacyApi();
        Method method = withdraw;
        if (api == null || method == null) {
            return CompletableFuture.completedFuture(TxResult.fail("economy-unavailable"));
        }
        return invokeAsync(api, method, uuid, playerName, amount, reason, "withdraw");
    }

    public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName, ShopCurrency currency,
                                                BigDecimal amount, String reason) {
        ShopCurrency effective = currency == null ? ShopCurrency.MONEY : currency;
        if (effective == ShopCurrency.MONEY) {
            return withdraw(uuid, playerName, amount, reason);
        }
        return invokeCurrencyAsync(effective, uuid, playerName, amount, reason, false);
    }

    /** Legacy deposit MONEY. */
    public CompletableFuture<TxResult> deposit(UUID uuid, String playerName, BigDecimal amount, String reason) {
        Object api = resolveLegacyApi();
        Method method = deposit;
        if (api == null || method == null) {
            return CompletableFuture.completedFuture(TxResult.fail("economy-unavailable"));
        }
        return invokeAsync(api, method, uuid, playerName, amount, reason, "deposit");
    }

    public CompletableFuture<TxResult> deposit(UUID uuid, String playerName, ShopCurrency currency,
                                               BigDecimal amount, String reason) {
        ShopCurrency effective = currency == null ? ShopCurrency.MONEY : currency;
        if (effective == ShopCurrency.MONEY) {
            return deposit(uuid, playerName, amount, reason);
        }
        return invokeCurrencyAsync(effective, uuid, playerName, amount, reason, true);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<TxResult> invokeAsync(Object api, Method method, UUID uuid, String playerName,
                                                    BigDecimal amount, String reason, String label) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object raw = method.invoke(api, uuid, playerName, amount, reason);
                return adaptResult(raw);
            } catch (Throwable t) {
                Throwable root = rootCause(t);
                logFine("HexNPC: economy " + label + " failed: " + safeMessage(root));
                return TxResult.fail(safeMessageOr(root, "error"));
            }
        }, executor);
    }

    private CompletableFuture<TxResult> invokeCurrencyAsync(ShopCurrency currency, UUID uuid,
                                                            String playerName, BigDecimal amount, String reason,
                                                            boolean depositOperation) {
        Object api = resolveMultiCurrencyApi();
        Object currencyValue = resolveCurrencyValue(currency);
        Method method = depositOperation ? multiDeposit : multiWithdraw;
        if (api == null || method == null || currencyValue == null) {
            return CompletableFuture.completedFuture(TxResult.fail("economy-unavailable"));
        }
        if (!isMultiCurrencyAvailable(api, currencyValue)) {
            return CompletableFuture.completedFuture(TxResult.fail("economy-unavailable"));
        }
        String label = depositOperation ? "deposit" : "withdraw";
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object raw = method.invoke(api, uuid, playerName, currencyValue, amount, reason);
                return adaptResult(raw);
            } catch (Throwable t) {
                Throwable root = rootCause(t);
                logFine("HexNPC: economy " + label + " (" + currency + ") failed: " + safeMessage(root));
                return TxResult.fail(safeMessageOr(root, "error"));
            }
        }, executor);
    }

    private boolean isMultiCurrencyAvailable(Object api, Object currencyValue) {
        Method method = isCurrencyAvailable;
        if (method == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(api, currencyValue));
        } catch (Throwable t) {
            logFine("HexNPC: multi-currency availability check failed: " + rootMessage(t));
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveCurrencyValue(ShopCurrency currency) {
        Class<?> type = currencyClass;
        if (type == null || !type.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), currency.name());
        } catch (IllegalArgumentException | LinkageError ex) {
            return null;
        }
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
            return TxResult.fail(safeMessageOr(rootCause(t), "result-error"));
        }
    }

    private synchronized void ensureResultAccessors(Class<?> resultType) throws NoSuchMethodException {
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

    /**
     * Rozwiązuje wyłącznie historyczny surface HexEconomy potrzebny dla MONEY.
     * Nie ładuje CurrencyType i nie szuka żadnych metod multi-currency.
     */
    private Object resolveLegacyApi() {
        Plugin plugin = lookupEconomyPlugin();
        if (plugin == null) {
            invalidate();
            return null;
        }

        Object instance = apiInstance;
        if (instance != null && plugin == cachedPlugin && stillRegistered(instance)) {
            return instance;
        }

        synchronized (this) {
            instance = apiInstance;
            if (instance != null && plugin == cachedPlugin && stillRegistered(instance)) {
                return instance;
            }

            // Zmiana/reload instancji HexEconomy unieważnia także opcjonalny cache multi.
            invalidate();

            Class<?> type;
            try {
                type = Class.forName(API_CLASS, true, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError ex) {
                if (loggedMissing.compareAndSet(false, true)) {
                    log("HexNPC: HexEconomy is loaded but legacy API " + API_CLASS
                            + " is unavailable: " + safeMessage(ex));
                }
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

            Object provider;
            try {
                @SuppressWarnings("rawtypes")
                RegisteredServiceProvider rsp = sm.getRegistration((Class) type);
                if (rsp == null) {
                    apiClass = type;
                    cachedPlugin = plugin;
                    return null;
                }
                provider = rsp.getProvider();
            } catch (LinkageError | RuntimeException ex) {
                logFine("HexNPC: failed to resolve HexEconomy legacy service: " + safeMessage(ex));
                return null;
            }
            if (provider == null) {
                return null;
            }

            Method legacyDeposit;
            Method legacyWithdraw;
            Method legacyFormat;
            Method legacyCurrencyName;
            try {
                legacyDeposit = type.getMethod("deposit", UUID.class, String.class, BigDecimal.class, String.class);
                legacyWithdraw = type.getMethod("withdraw", UUID.class, String.class, BigDecimal.class, String.class);
                legacyFormat = type.getMethod("format", BigDecimal.class);
                legacyCurrencyName = type.getMethod("currencyName");
            } catch (NoSuchMethodException | LinkageError ex) {
                log("HexNPC: HexEconomy legacy API surface mismatch: " + safeMessage(ex));
                invalidate();
                return null;
            }

            this.apiClass = type;
            this.cachedPlugin = plugin;
            this.apiInstance = provider;
            this.deposit = legacyDeposit;
            this.withdraw = legacyWithdraw;
            this.format = legacyFormat;
            this.currencyName = legacyCurrencyName;
            clearMultiApi();

            if (loggedAvailable.compareAndSet(false, true)) {
                log("HexNPC: HexEconomy legacy MONEY bridge ready (currency=" + safeCurrency() + ").");
            }
            loggedMissing.set(false);
            return provider;
        }
    }

    /**
     * Rozwiązuje opcjonalny surface multi-currency dopiero na żądanie sklepu
     * HEX_COINS. Nie modyfikuje metod legacy i przy błędzie zwraca null.
     */
    private Object resolveMultiCurrencyApi() {
        Object provider = resolveLegacyApi();
        if (provider == null) {
            return null;
        }
        if (multiResolved) {
            return hasMultiApi() ? provider : null;
        }

        synchronized (this) {
            provider = resolveLegacyApi();
            if (provider == null) {
                return null;
            }
            if (multiResolved) {
                return hasMultiApi() ? provider : null;
            }

            Plugin plugin = cachedPlugin;
            Class<?> type = apiClass;
            if (plugin == null || type == null) {
                multiResolved = true;
                return null;
            }

            try {
                Class<?> ct = Class.forName(CURRENCY_CLASS, true, plugin.getClass().getClassLoader());
                Method md = type.getMethod("deposit", UUID.class, String.class, ct, BigDecimal.class, String.class);
                Method mw = type.getMethod("withdraw", UUID.class, String.class, ct, BigDecimal.class, String.class);
                Method mf = type.getMethod("format", ct, BigDecimal.class);
                Method mcn = type.getMethod("currencyName", ct);
                Method available;
                try {
                    available = type.getMethod("isCurrencyAvailable", ct);
                } catch (NoSuchMethodException ignored) {
                    available = null;
                }

                this.currencyClass = ct;
                this.multiDeposit = md;
                this.multiWithdraw = mw;
                this.multiFormat = mf;
                this.multiCurrencyName = mcn;
                this.isCurrencyAvailable = available;
                this.multiResolved = true;
                loggedMultiUnavailable.set(false);
                if (loggedMultiAvailable.compareAndSet(false, true)) {
                    log("HexNPC: HexEconomy HEX_COINS multi-currency bridge ready.");
                }
                return provider;
            } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
                clearMultiApi();
                this.multiResolved = true;
                if (loggedMultiUnavailable.compareAndSet(false, true)) {
                    log("HexNPC: HexEconomy multi-currency API unavailable; HEX_COINS shops are disabled, "
                            + "MONEY remains on legacy API. Cause: " + safeMessage(ex));
                }
                return null;
            }
        }
    }

    private boolean hasMultiApi() {
        return currencyClass != null
                && multiDeposit != null
                && multiWithdraw != null
                && multiFormat != null
                && multiCurrencyName != null;
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
        Class<?> type = apiClass;
        if (type == null) {
            return false;
        }
        try {
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration((Class) type);
            return rsp != null && rsp.getProvider() == instance;
        } catch (Throwable t) {
            return false;
        }
    }

    private synchronized void invalidate() {
        apiInstance = null;
        apiClass = null;
        cachedPlugin = null;
        deposit = null;
        withdraw = null;
        format = null;
        currencyName = null;
        resultSuccess = null;
        resultBalance = null;
        resultReason = null;
        clearMultiApi();
    }

    private void clearMultiApi() {
        multiResolved = false;
        currencyClass = null;
        multiDeposit = null;
        multiWithdraw = null;
        multiFormat = null;
        multiCurrencyName = null;
        isCurrencyAvailable = null;
    }

    private void log(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }

    private void logFine(String message) {
        if (logger != null) {
            logger.fine(message);
        }
    }

    private String safeCurrency() {
        try {
            Method method = currencyName;
            Object api = apiInstance;
            return method == null || api == null ? "?" : String.valueOf(method.invoke(api));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Throwable rootCause(Throwable t) {
        return t != null && t.getCause() != null ? t.getCause() : t;
    }

    private static String rootMessage(Throwable t) {
        return safeMessage(rootCause(t));
    }

    private static String safeMessage(Throwable t) {
        return t == null || t.getMessage() == null ? t == null ? "unknown" : t.getClass().getSimpleName()
                : t.getMessage();
    }

    private static String safeMessageOr(Throwable t, String fallback) {
        String msg = safeMessage(t);
        return msg == null || msg.isBlank() ? fallback : msg;
    }

    private static String fallbackHexCoins(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + " HexCoins";
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
