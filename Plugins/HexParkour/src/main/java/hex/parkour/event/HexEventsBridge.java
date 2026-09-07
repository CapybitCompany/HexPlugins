package hex.parkour.event;

import hex.events.api.HexEventModule;
import hex.events.api.HexEventsApi;
import hex.events.api.LeaveReason;
import hex.events.api.ModuleRegistration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HexEventsBridge {
    private final Plugin plugin;
    private final HexEventsApi api;

    private HexEventsBridge(Plugin plugin, HexEventsApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    public static HexEventsBridge create(Plugin plugin) {
        RegisteredServiceProvider<HexEventsApi> registration = Bukkit.getServicesManager().getRegistration(HexEventsApi.class);
        if (registration == null) return null;
        return new HexEventsBridge(plugin, registration.getProvider());
    }

    public ModuleRegistration registerModule(HexEventModule module) {
        return api.registerModule(module);
    }

    public boolean leave(Player player, UUID instanceId, LeaveReason reason) {
        if (tryPublicLeave(player, instanceId, reason)) return true;
        return tryLifecycleLeave(player, instanceId, reason);
    }

    private boolean tryPublicLeave(Player player, UUID instanceId, LeaveReason reason) {
        try {
            Method requestLeave = api.getClass().getMethod("requestLeave", UUID.class, UUID.class, LeaveReason.class);
            Object result = requestLeave.invoke(api, player.getUniqueId(), instanceId, reason);
            if (result instanceof CompletableFuture<?> future) {
                future.exceptionally(error -> {
                    plugin.getLogger().warning("HexEvents requestLeave failed: " + rootMessage(error));
                    return null;
                });
            }
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable error) {
            plugin.getLogger().warning("HexEvents requestLeave bridge failed: " + rootMessage(error));
            return false;
        }
    }

    private boolean tryLifecycleLeave(Player player, UUID instanceId, LeaveReason reason) {
        try {
            Field lifecycleField = api.getClass().getDeclaredField("lifecycle");
            lifecycleField.setAccessible(true);
            Object lifecycle = lifecycleField.get(api);
            Method leave = lifecycle.getClass().getMethod("leave", Player.class, UUID.class, LeaveReason.class);
            leave.invoke(lifecycle, player, instanceId, reason);
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("HexEvents lifecycle leave bridge failed: " + rootMessage(error));
            return false;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
