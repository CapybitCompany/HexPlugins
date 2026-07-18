package hexdailyrewards.integration;

import hexdailyrewards.config.DailyRewardsConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class HexNpcActionBridge implements Listener {

    private final JavaPlugin plugin;
    private final Supplier<DailyRewardsConfig> configSupplier;
    private final Consumer<Player> opener;
    private String registeredActionId;

    public HexNpcActionBridge(JavaPlugin plugin,
                              Supplier<DailyRewardsConfig> configSupplier,
                              Consumer<Player> opener) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    public void refresh() {
        unregister();
        DailyRewardsConfig config = configSupplier.get();
        if (config == null || !config.hexNpc().enabled()) {
            return;
        }
        register(config.hexNpc().actionId());
    }

    public void unregister() {
        if (registeredActionId == null) {
            return;
        }
        try {
            Object registry = registry();
            if (registry != null) {
                Method unregister = registry.getClass().getMethod("unregister", String.class);
                unregister.invoke(registry, registeredActionId);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("HexDailyRewards: failed to unregister HexNPC action: " + ex.getMessage());
        } finally {
            registeredActionId = null;
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("HexNPC")) {
            refresh();
        }
    }

    private void register(String rawActionId) {
        String actionId = normalize(rawActionId);
        if (actionId.isBlank()) {
            plugin.getLogger().warning("HexDailyRewards: hexnpc.action-id is empty; HexNPC bridge disabled.");
            return;
        }
        try {
            Class<?> registryClass = Class.forName("hexnpc.service.NpcActionRegistry");
            Class<?> handlerClass = Class.forName("hexnpc.action.NpcActionHandler");
            Object registry = registry(registryClass);
            if (registry == null) {
                return;
            }

            Object handler = Proxy.newProxyInstance(
                    handlerClass.getClassLoader(),
                    new Class<?>[]{handlerClass},
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "id" -> actionId;
                        case "execute" -> {
                            if (methodArgs != null && methodArgs.length > 0 && methodArgs[0] instanceof Player player) {
                                opener.accept(player);
                            }
                            yield null;
                        }
                        case "toString" -> "HexDailyRewardsActionHandler{" + actionId + "}";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> methodArgs != null && methodArgs.length == 1 && proxy == methodArgs[0];
                        default -> null;
                    }
            );
            Method register = registryClass.getMethod("register", handlerClass);
            register.invoke(registry, handler);
            registeredActionId = actionId;
            plugin.getLogger().info("HexDailyRewards registered HexNPC action: " + actionId);
        } catch (ClassNotFoundException ignored) {
            plugin.getLogger().info("HexNPC not detected. HexDailyRewards action bridge is waiting.");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("HexDailyRewards: failed to register HexNPC action: " + ex.getMessage());
        }
    }

    private Object registry() throws ReflectiveOperationException {
        Class<?> registryClass = Class.forName("hexnpc.service.NpcActionRegistry");
        return registry(registryClass);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object registry(Class<?> registryClass) {
        RegisteredServiceProvider provider = Bukkit.getServicesManager().getRegistration((Class) registryClass);
        return provider == null ? null : provider.getProvider();
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}

