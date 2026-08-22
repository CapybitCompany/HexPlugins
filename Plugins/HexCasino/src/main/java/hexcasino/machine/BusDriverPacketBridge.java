package hexcasino.machine;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/** PacketEvents adapter binding BusDriver decisions to the exact client container revision. */
public final class BusDriverPacketBridge {
    private final JavaPlugin plugin;
    private final BusDriverService service;
    private PacketListenerCommon listener;
    private boolean ready;

    public BusDriverPacketBridge(JavaPlugin plugin, BusDriverService service) {
        this.plugin = Objects.requireNonNull(plugin);
        this.service = Objects.requireNonNull(service);
    }

    public void start() {
        stop();
        try {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
                ready = false;
                plugin.getLogger().warning("PacketEvents is not enabled. Deterministic BusDriver paid decisions are disabled.");
                return;
            }
            listener = new PacketListenerAbstract(PacketListenerPriority.LOW) {
                @Override public void onPacketSend(PacketSendEvent event) {
                    if (!(event.getPlayer() instanceof Player player)) return;
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                        service.onContainerRevisionSent(player.getUniqueId(), wrapper.getWindowId(), wrapper.getStateId());
                    } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                        service.onContainerRevisionSent(player.getUniqueId(), wrapper.getWindowId(), wrapper.getStateId());
                    }
                }

                @Override public void onPacketReceive(PacketReceiveEvent event) {
                    if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;
                    if (!(event.getPlayer() instanceof Player player)) return;
                    WrapperPlayClientClickWindow wrapper = new WrapperPlayClientClickWindow(event);
                    int stateId = wrapper.getStateId().orElse(-1);
                    if (stateId < 0) return;
                    if (!service.isStrictDecisionPacket(player.getUniqueId(), wrapper.getSlot())) return;
                    event.setCancelled(true);
                    service.onStrictDecisionPacket(player.getUniqueId(), wrapper.getWindowId(), stateId,
                            wrapper.getSlot(), System.nanoTime());
                }
            };
            PacketEvents.getAPI().getEventManager().registerListener(listener);
            ready = true;
            plugin.getLogger().info("BusDriver packet state resolver enabled through PacketEvents.");
        } catch (Throwable ex) {
            listener = null;
            ready = false;
            plugin.getLogger().severe("BusDriver PacketEvents bridge failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public void stop() {
        if (listener != null) {
            try { PacketEvents.getAPI().getEventManager().unregisterListener(listener); } catch (Throwable ignored) {}
        }
        listener = null;
        ready = false;
    }

    public boolean ready(){ return ready; }
}
