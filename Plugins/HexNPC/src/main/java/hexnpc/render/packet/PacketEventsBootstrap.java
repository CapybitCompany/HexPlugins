package hexnpc.render.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Thin guard around PacketEvents API calls. Touching this class on a server
 * without PacketEvents loaded will raise NoClassDefFoundError on first use,
 * so the caller MUST check {@link #isAvailable()} first.
 */
public final class PacketEventsBootstrap {

    private PacketEventsBootstrap() {
    }

    public static boolean isAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("packetevents");
        return plugin != null && plugin.isEnabled();
    }

    public static void registerListener(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public static void unregisterListener(PacketListenerCommon listener) {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (Throwable ignored) {
            // PacketEvents may have already shut down or never been started.
        }
    }
}
