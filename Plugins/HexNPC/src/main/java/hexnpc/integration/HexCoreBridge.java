package hexnpc.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Optional, read-only bridge to HexCore. Resolved entirely via reflection so
 * HexNPC has no compile-time dependency on {@code hex.core.*} and continues
 * to load when HexCore is absent. v1 only exposes a generic service lookup
 * that returns {@code Optional<Object>} — callers cast where needed.
 *
 * Intentional non-goals for v1:
 *  - Do not import HexCore classes.
 *  - Do not mutate HexCore state.
 *  - Do not require HexCore to expose new API surface.
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
            // HexCore exposes its services through a single accessor on the plugin
            // class. We look for any zero-arg method whose return type is HexApi
            // (matched by simple name to avoid a compile-time dependency).
            Method apiAccessor = findApiAccessor(plugin);
            if (apiAccessor == null) {
                invalidate();
                return null;
            }
            apiAccessor.setAccessible(true);
            Object api = apiAccessor.invoke(plugin);
            if (api == null) {
                invalidate();
                return null;
            }
            Method lookup = api.getClass().getMethod("service", Class.class);
            cachedPlugin = plugin;
            cachedApi = api;
            serviceLookup = lookup;
            return api;
        } catch (Exception ex) {
            logger.fine("HexNPC: HexCore reflection probe failed: " + ex.getMessage());
            invalidate();
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
