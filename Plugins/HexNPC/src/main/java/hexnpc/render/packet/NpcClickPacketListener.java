package hexnpc.render.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.render.NpcRenderer;
import hexnpc.service.NpcInteractionService;
import hexnpc.service.NpcService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class NpcClickPacketListener extends PacketListenerAbstract {

    private final Plugin plugin;
    private final NpcRenderer renderer;
    private final NpcService npcService;
    private final NpcInteractionService interactionService;

    public NpcClickPacketListener(Plugin plugin,
                                  NpcRenderer renderer,
                                  NpcService npcService,
                                  NpcInteractionService interactionService) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.npcService = Objects.requireNonNull(npcService, "npcService");
        this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        int entityId = wrapper.getEntityId();
        Optional<NpcId> id = renderer.lookupByEntityId(entityId);
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
        // Click handling must run on the main thread — packets arrive on netty threads.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            interactionService.trigger(player, npc.get(), InteractionTrigger.CLICK);
        });
    }
}
