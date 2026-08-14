package hexnpc.render.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import hexnpc.HexNpcPlugin;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.render.NpcRenderer;
import hexnpc.service.NpcInteractionService;
import hexnpc.service.NpcItemUseSuppressor;
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
    private final NpcItemUseSuppressor itemUseSuppressor;

    public NpcClickPacketListener(HexNpcPlugin plugin, NpcItemUseSuppressor itemUseSuppressor) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemUseSuppressor = Objects.requireNonNull(itemUseSuppressor, "itemUseSuppressor");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (isUseItemPacket(event.getPacketType())) {
            cancelSuppressedItemUse(event);
            return;
        }
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

        event.setCancelled(true);
        if (isNpcRightClick(wrapper.getAction(), wrapper.getHand())) {
            itemUseSuppressor.suppress(playerId);
            clearActiveItem(playerId);
        }
        if (!shouldHandle(wrapper.getAction(), wrapper.getHand())) {
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

    private void cancelSuppressedItemUse(PacketReceiveEvent event) {
        UUID playerId = event.getUser().getUUID();
        if (playerId != null && itemUseSuppressor.shouldCancelUse(playerId)) {
            event.setCancelled(true);
            clearActiveItem(playerId);
        }
    }

    private void clearActiveItem(UUID playerId) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && player.hasActiveItem()) {
                player.clearActiveItem();
            }
        });
    }

    /**
     * Decides which InteractAction fires the HexNPC CLICK trigger. Vanilla
     * right-clicks can send INTERACT_AT and INTERACT; only INTERACT should
     * trigger the action, but both are NPC right-clicks for item suppression.
     */
    static boolean shouldHandle(WrapperPlayClientInteractEntity.InteractAction action) {
        return shouldHandle(action, InteractionHand.MAIN_HAND);
    }

    static boolean shouldHandle(WrapperPlayClientInteractEntity.InteractAction action, InteractionHand hand) {
        return action == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                && hand == InteractionHand.MAIN_HAND;
    }

    static boolean isNpcRightClick(WrapperPlayClientInteractEntity.InteractAction action, InteractionHand hand) {
        return (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                || action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT)
                && hand != null;
    }

    static boolean isUseItemPacket(Object packetType) {
        return packetType == PacketType.Play.Client.USE_ITEM
                || packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT;
    }
}
