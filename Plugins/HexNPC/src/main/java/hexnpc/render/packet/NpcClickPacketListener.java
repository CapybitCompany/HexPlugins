package hexnpc.render.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import hexnpc.HexNpcPlugin;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.render.NpcRenderer;
import hexnpc.service.NpcInteractionService;
import hexnpc.service.NpcService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Registered once during {@link HexNpcPlugin#onEnable()} and never re-registered
 * on reload. Reads renderer / services lazily from the plugin so a reload that
 * rebuilds them is transparent.
 */
public final class NpcClickPacketListener extends PacketListenerAbstract {

    private final HexNpcPlugin plugin;

    public NpcClickPacketListener(HexNpcPlugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        NpcRenderer renderer = plugin.renderer();
        NpcService npcService = plugin.npcService();
        NpcInteractionService interactionService = plugin.interactionService();
        if (renderer == null || npcService == null || interactionService == null) {
            return;
        }

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        // Rechtsklick auf eine Entity sendet zwei Pakete: INTERACT_AT und INTERACT.
        // Wir reagieren nur auf INTERACT, sonst feuert der Trigger doppelt.
        // ATTACK ist Linksklick und gehört nicht zu CLICK-Interaktion.
        if (!shouldHandle(wrapper.getAction())) {
            return;
        }
        Optional<NpcId> id = renderer.lookupByEntityId(wrapper.getEntityId());
        if (id.isEmpty()) {
            return;
        }
        UUID playerId = event.getUser().getUUID();
        if (playerId == null) {
            return;
        }
        Optional<NpcDefinition> npc = npcService.find(id.get());
        if (npc.isEmpty()) {
            return;
        }
        // Packets arrive on netty threads. Bukkit entity / scheduler work needs main thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            NpcInteractionService freshInteraction = plugin.interactionService();
            NpcService freshNpcService = plugin.npcService();
            if (freshInteraction == null || freshNpcService == null) {
                return;
            }
            freshNpcService.find(id.get())
                    .ifPresent(def -> freshInteraction.trigger(player, def, InteractionTrigger.CLICK));
        });
    }

    /**
     * Entscheidet, welche InteractAction zu unserer CLICK-Interaktion zählt.
     * <ul>
     *   <li>{@code INTERACT} – echter Rechtsklick (mainhand). Akzeptiert.</li>
     *   <li>{@code INTERACT_AT} – Vorab-Paket zum Rechtsklick mit Trefferpunkt. Ignoriert,
     *       sonst feuert der Trigger zweimal pro Klick.</li>
     *   <li>{@code ATTACK} – Linksklick. Ignoriert.</li>
     * </ul>
     */
    static boolean shouldHandle(WrapperPlayClientInteractEntity.InteractAction action) {
        return action == WrapperPlayClientInteractEntity.InteractAction.INTERACT;
    }
}
