package hex.drawn.integration;

import hex.drawn.WaterDrawnPlugin;
import org.bukkit.Bukkit;

import java.lang.reflect.Proxy;
import java.util.Optional;

public class HexMessageBridge {

    private static final String CHANNEL = "flood.water-level";

    private final WaterDrawnPlugin plugin;
    private Object listenerProxy;

    public HexMessageBridge(WaterDrawnPlugin plugin) {
        this.plugin = plugin;
    }

    public void trySubscribe() {
        try {
            if (Bukkit.getPluginManager().getPlugin("HexCore") == null) {
                plugin.getLogger().info("[MessageBus] HexCore not found.");
                return;
            }

            Class<?> hexApiClass = Class.forName("hex.core.api.HexApi");
            var registration = Bukkit.getServicesManager().getRegistration((Class) hexApiClass);
            if (registration == null) {
                return;
            }

            Object apiProvider = registration.getProvider();
            Class<?> messageBusClass = Class.forName("hex.core.api.messaging.HexMessageBus");
            Optional<?> busOpt = (Optional<?>) apiProvider.getClass()
                    .getMethod("service", Class.class)
                    .invoke(apiProvider, messageBusClass);
            if (busOpt.isEmpty()) {
                return;
            }

            Object bus = busOpt.get();
            Class<?> listenerClass = Class.forName("hex.core.api.messaging.HexMessageListener");

            this.listenerProxy = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if (!"onMessage".equals(method.getName()) || args == null || args.length != 1) {
                            return null;
                        }

                        Object message = args[0];
                        Object messageData = message.getClass().getMethod("data").invoke(message);
                        int level = (int) messageData.getClass()
                                .getMethod("getInt", String.class, int.class)
                                .invoke(messageData, "current_level", Integer.MAX_VALUE);
                        plugin.setRuntimeWaterLevel(level);
                        return null;
                    }
            );

            bus.getClass().getMethod("subscribe", String.class, listenerClass)
                    .invoke(bus, CHANNEL, listenerProxy);
            plugin.getLogger().info("[MessageBus] Subscribed to channel '" + CHANNEL + "'.");
        } catch (Throwable ex) {
            plugin.getLogger().info("[MessageBus] Skipped: " + ex.getMessage());
        }
    }

    public void tryUnsubscribe() {
        if (listenerProxy == null) {
            return;
        }

        try {
            Class<?> hexApiClass = Class.forName("hex.core.api.HexApi");
            var registration = Bukkit.getServicesManager().getRegistration((Class) hexApiClass);
            if (registration == null) {
                return;
            }

            Object apiProvider = registration.getProvider();
            Class<?> messageBusClass = Class.forName("hex.core.api.messaging.HexMessageBus");
            Optional<?> busOpt = (Optional<?>) apiProvider.getClass()
                    .getMethod("service", Class.class)
                    .invoke(apiProvider, messageBusClass);
            if (busOpt.isEmpty()) {
                return;
            }

            Object bus = busOpt.get();
            Class<?> listenerClass = Class.forName("hex.core.api.messaging.HexMessageListener");
            bus.getClass().getMethod("unsubscribe", String.class, listenerClass)
                    .invoke(bus, CHANNEL, listenerProxy);
        } catch (Throwable ignored) {
            // HexCore missing during shutdown.
        }
    }
}
