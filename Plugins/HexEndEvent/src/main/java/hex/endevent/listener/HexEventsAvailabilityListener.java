package hex.endevent.listener;

import hex.endevent.HexEndEventPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/** Keeps the End fail-closed when the central event manager disappears at runtime. */
public final class HexEventsAvailabilityListener implements Listener {
    private final HexEndEventPlugin plugin;

    public HexEventsAvailabilityListener(HexEndEventPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("HexEvents")) plugin.handleHexEventsDisabled();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("HexEvents")) plugin.handleHexEventsEnabled();
    }
}
