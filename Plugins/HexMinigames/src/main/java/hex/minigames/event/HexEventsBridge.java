package hex.minigames.event;

import hex.events.api.EventFailure;
import hex.events.api.EventResult;
import hex.events.api.HexEventModule;
import hex.events.api.HexEventsApi;
import hex.events.api.ModuleRegistration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

public final class HexEventsBridge {
    private final HexEventsApi api;

    private HexEventsBridge(HexEventsApi api) {
        this.api = api;
    }

    public static HexEventsBridge create(Plugin plugin) {
        RegisteredServiceProvider<HexEventsApi> registration = Bukkit.getServicesManager().getRegistration(HexEventsApi.class);
        if (registration == null) return null;
        return new HexEventsBridge(registration.getProvider());
    }

    public ModuleRegistration registerModule(HexEventModule module) {
        return api.registerModule(module);
    }

    public boolean complete(UUID instanceId, EventResult result) {
        return api.complete(instanceId, result);
    }

    public boolean fail(UUID instanceId, EventFailure failure) {
        return api.fail(instanceId, failure);
    }
}
