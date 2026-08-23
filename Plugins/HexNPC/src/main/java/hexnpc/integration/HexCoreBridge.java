package hexnpc.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Optional bridge to HexCore, resolved entirely via reflection so HexNPC has
 * no compile-time dependency on {@code hex.core.*} and keeps loading when
 * HexCore is absent. Exposes a generic service lookup plus the shared
 * {@code DatabaseService} (used by the append-only shop audit log).
 *
 * <p>HexNPC never opens its own JDBC connection or pool: all persistence goes
 * through HexCore's {@code DatabaseService}, whose connection pool and async
 * executor are owned by HexCore. HexNPC must never call
 * {@code DatabaseService#shutdown()}.
 *
 * <p>Design constraints:
 *  <ul>
 *    <li>Do not import HexCore classes at compile time.</li>
 *    <li>Do not mutate HexCore's own state or lifecycle.</li>
 *    <li>Writes are limited to HexNPC-owned tables via HexCore's API.</li>
 *  </ul>
 */
public final class HexCoreBridge {

    private static final String PLUGIN_NAME = "HexCore";

    private final Logger logger;

    private Plugin cachedPlugin;
    private Object cachedApi;
    private Method serviceLookup;

    public HexCoreBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean isAvailable() {
        return resolveApi() != null;
    }

    /**
     * Generic service lookup. Returns Optional.empty() if HexCore is absent,
     * its API surface has changed, or the requested service is unavailable.
     */
    public Optional<Object> service(Class<?> type) {
        Object api = resolveApi();
        if (api == null || serviceLookup == null) {
            return Optional.empty();
        }
        try {
            Object result = serviceLookup.invoke(api, type);
            if (result instanceof Optional<?> opt) {
                return opt.map(Object.class::cast);
            }
            return Optional.ofNullable(result);
        } catch (Exception ex) {
            logger.fine("HexNPC: HexCore service(" + type.getName() + ") failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Zwraca współdzielony {@code hex.core.api.db.DatabaseService} HexCore
     * (jako Object, przez reflection) lub {@link Optional#empty()}, gdy HexCore
     * jest nieobecny albo jego API się zmieniło. HexNPC nie jest właścicielem
     * puli i nigdy jej nie zamyka.
     */
    public Optional<Object> databaseService() {
        Object api = resolveApi();
        if (api == null) {
            return Optional.empty();
        }
        try {
            Method dbAccessor = api.getClass().getMethod("db");
            dbAccessor.setAccessible(true);
            return Optional.ofNullable(dbAccessor.invoke(api));
        } catch (Exception ex) {
            logger.fine("HexNPC: HexCore db() lookup failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private Object resolveApi() {
        PluginManager pm = Bukkit.getPluginManager();
        Plugin plugin = pm.getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            invalidate();
            return null;
        }
        if (plugin == cachedPlugin && cachedApi != null && serviceLookup != null) {
            return cachedApi;
        }
        try {
            /*
             * HexCore's supported public integration point is Bukkit ServicesManager:
             *
             *   Bukkit.getServicesManager().register(HexApi.class, api, ...)
             *
             * HexCore does NOT have to expose a public api()/getApi() method on its
             * JavaPlugin class. The old bridge assumed such a getter existed, so on
             * the current HexCore it always resolved null and disabled player-data
             * backed workflows (including the premium custom tag).
             *
             * Keep this compile-time-decoupled: resolve HexApi through HexCore's own
             * PluginClassLoader and use the raw Class object only for ServicesManager.
             */
            Object api = loadRegisteredHexApi(plugin);

            // Backward-compatible fallback for an older/custom HexCore that exposes
            // HexApi directly but does not register it in ServicesManager.
            if (api == null) {
                Method apiAccessor = findApiAccessor(plugin);
                if (apiAccessor != null) {
                    apiAccessor.setAccessible(true);
                    api = apiAccessor.invoke(plugin);
                }
            }

            if (api == null) {
                logger.fine("HexNPC: HexCore is enabled but no HexApi provider could be resolved.");
                invalidate();
                return null;
            }

            Method lookup = api.getClass().getMethod("service", Class.class);
            cachedPlugin = plugin;
            cachedApi = api;
            serviceLookup = lookup;
            return api;
        } catch (Throwable ex) {
            logger.fine("HexNPC: HexCore reflection/services probe failed: " + ex.getMessage());
            invalidate();
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object loadRegisteredHexApi(Plugin plugin) {
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> apiType = Class.forName("hex.core.api.HexApi", false, loader);
            return Bukkit.getServicesManager().load((Class) apiType);
        } catch (Throwable ex) {
            logger.fine("HexNPC: HexCore ServicesManager lookup failed: " + ex.getMessage());
            return null;
        }
    }

    private Method findApiAccessor(Plugin plugin) {
        for (Method method : plugin.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String returnSimple = method.getReturnType().getSimpleName();
            if (returnSimple.equals("HexApi")) {
                return method;
            }
        }
        return null;
    }

    private void invalidate() {
        cachedPlugin = null;
        cachedApi = null;
        serviceLookup = null;
    }
}
