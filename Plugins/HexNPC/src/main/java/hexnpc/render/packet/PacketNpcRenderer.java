package hexnpc.render.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import hexnpc.config.HexNpcConfig;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import hexnpc.render.NpcHandle;
import hexnpc.render.NpcRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PacketEvents-backed NPC renderer. All packet imports are confined to this
 * package so business logic in {@code service/} and {@code listener/}
 * never sees PacketEvents or NMS classes.
 */
public final class PacketNpcRenderer implements NpcRenderer {

    private static final AtomicInteger ENTITY_ID = new AtomicInteger(100_000_000);

    private final Plugin plugin;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Logger logger;

    private final Map<NpcId, RenderedNpc> bySupervisor = new HashMap<>();
    private final Map<Integer, NpcId> byEntityId = new HashMap<>();

    public PacketNpcRenderer(Plugin plugin,
                             Supplier<HexNpcConfig> configSupplier,
                             Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void start() {
        // Packet listeners are registered by HexNpcPlugin once PacketEvents is initialized.
    }

    @Override
    public void stop() {
        for (RenderedNpc rendered : new ArrayList<>(bySupervisor.values())) {
            for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    sendDestroy(viewer, rendered.entityId);
                }
            }
        }
        bySupervisor.clear();
        byEntityId.clear();
    }

    @Override
    public synchronized NpcHandle spawn(NpcDefinition definition) {
        RenderedNpc existing = bySupervisor.get(definition.id());
        if (existing != null) {
            existing.definition = definition;
            return existing;
        }
        RenderedNpc rendered = new RenderedNpc(ENTITY_ID.getAndIncrement(), definition, randomNpcUuid());
        bySupervisor.put(definition.id(), rendered);
        byEntityId.put(rendered.entityId, definition.id());

        double radius = configSupplier.get().render().viewDistanceBlocks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inSameWorld(player, rendered) && inRange(player, rendered, radius)) {
                renderFor(player, rendered);
            }
        }
        return rendered;
    }

    @Override
    public synchronized void despawn(NpcId id) {
        RenderedNpc rendered = bySupervisor.remove(id);
        if (rendered == null) {
            return;
        }
        byEntityId.remove(rendered.entityId);
        for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendDestroy(viewer, rendered.entityId);
            }
        }
        rendered.viewers.clear();
    }

    @Override
    public synchronized void move(NpcDefinition updated) {
        RenderedNpc rendered = bySupervisor.get(updated.id());
        if (rendered == null) {
            return;
        }
        rendered.definition = updated;

        // Drop viewers that left the world, teleport the rest.
        Set<UUID> stillVisible = new HashSet<>();
        for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) {
                continue;
            }
            if (!inSameWorld(viewer, rendered)) {
                sendDestroy(viewer, rendered.entityId);
                continue;
            }
            sendTeleport(viewer, rendered);
            stillVisible.add(viewerId);
        }
        rendered.viewers.clear();
        rendered.viewers.addAll(stillVisible);

        // Pick up new viewers in range of the new location.
        double radius = configSupplier.get().render().viewDistanceBlocks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (rendered.viewers.contains(player.getUniqueId())) {
                continue;
            }
            if (inSameWorld(player, rendered) && inRange(player, rendered, radius)) {
                renderFor(player, rendered);
            }
        }
    }

    @Override
    public synchronized void rotate(NpcDefinition updated) {
        RenderedNpc rendered = bySupervisor.get(updated.id());
        if (rendered == null) {
            return;
        }
        rendered.definition = updated;
        for (UUID viewerId : rendered.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendRotation(viewer, rendered);
            }
        }
    }

    @Override
    public synchronized void showTo(Player player) {
        double radius = configSupplier.get().render().viewDistanceBlocks();
        for (RenderedNpc rendered : bySupervisor.values()) {
            if (!inSameWorld(player, rendered)) {
                continue;
            }
            if (!inRange(player, rendered, radius)) {
                continue;
            }
            if (rendered.viewers.contains(player.getUniqueId())) {
                continue;
            }
            renderFor(player, rendered);
        }
    }

    @Override
    public synchronized void hideFrom(Player player) {
        for (RenderedNpc rendered : bySupervisor.values()) {
            if (rendered.viewers.remove(player.getUniqueId())) {
                sendDestroy(player, rendered.entityId);
            }
        }
    }

    @Override
    public Optional<NpcHandle> handle(NpcId id) {
        RenderedNpc rendered = bySupervisor.get(id);
        return rendered == null ? Optional.empty() : Optional.of(rendered);
    }

    @Override
    public Optional<NpcId> lookupByEntityId(int entityId) {
        return Optional.ofNullable(byEntityId.get(entityId));
    }

    private void renderFor(Player player, RenderedNpc rendered) {
        try {
            sendPlayerInfoAdd(player, rendered);
            sendSpawn(player, rendered);
            sendRotation(player, rendered);
            sendSkinLayers(player, rendered.entityId);
            rendered.viewers.add(player.getUniqueId());

            int delay = configSupplier.get().render().tablistRemoveDelayTicks();
            if (delay > 0) {
                UUID uuid = rendered.profileUuid;
                UUID playerId = player.getUniqueId();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    Player current = Bukkit.getPlayer(playerId);
                    if (current != null && current.isOnline()) {
                        sendPlayerInfoRemove(current, uuid);
                    }
                }, delay);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                    "HexNPC: failed to render NPC " + rendered.definition.id() + " for " + player.getName(), ex);
        }
    }

    private void sendPlayerInfoAdd(Player viewer, RenderedNpc rendered) {
        NpcDefinition def = rendered.definition;
        UserProfile profile = new UserProfile(rendered.profileUuid, displayName(def));
        if (def.skin().hasTexture()) {
            profile.setTextureProperties(List.of(
                    new TextureProperty("textures", def.skin().value(), def.skin().signature())
            ));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, true, 0, GameMode.CREATIVE, Component.text(displayName(def)), null
        );
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(
                        WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
                ),
                List.of(info)
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private void sendPlayerInfoRemove(Player viewer, UUID profileUuid) {
        WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(
                Collections.singletonList(profileUuid));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private void sendSpawn(Player viewer, RenderedNpc rendered) {
        NpcLocation loc = rendered.definition.location();
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                rendered.entityId,
                Optional.of(rendered.profileUuid),
                EntityTypes.PLAYER,
                new Vector3d(loc.x(), loc.y(), loc.z()),
                loc.pitch(),
                loc.yaw(),
                loc.yaw(),
                0,
                Optional.empty()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private void sendRotation(Player viewer, RenderedNpc rendered) {
        NpcLocation loc = rendered.definition.location();
        WrapperPlayServerEntityRotation rotation = new WrapperPlayServerEntityRotation(
                rendered.entityId, loc.yaw(), loc.pitch(), true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, rotation);

        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(
                rendered.entityId, loc.yaw());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
    }

    private void sendTeleport(Player viewer, RenderedNpc rendered) {
        NpcLocation loc = rendered.definition.location();
        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                rendered.entityId,
                new Vector3d(loc.x(), loc.y(), loc.z()),
                loc.yaw(),
                loc.pitch(),
                true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleport);

        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(
                rendered.entityId, loc.yaw());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
    }

    private void sendSkinLayers(Player viewer, int entityId) {
        // Byte 17 = "skin parts visible" bit mask. 0x7F = all layers on.
        EntityData data = new EntityData(17, EntityDataTypes.BYTE, (byte) 0x7F);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                entityId, List.of(data));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    private void sendDestroy(Player viewer, int entityId) {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(entityId);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
    }

    private String displayName(NpcDefinition def) {
        if (def.skin().name() != null && !def.skin().name().isEmpty()) {
            return def.skin().name();
        }
        return def.id().value();
    }

    private boolean inSameWorld(Player player, RenderedNpc rendered) {
        return player.getWorld().getName().equalsIgnoreCase(rendered.definition.location().world());
    }

    private boolean inRange(Player player, RenderedNpc rendered, double radius) {
        NpcLocation loc = rendered.definition.location();
        double dx = player.getLocation().getX() - loc.x();
        double dy = player.getLocation().getY() - loc.y();
        double dz = player.getLocation().getZ() - loc.z();
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }

    private static UUID randomNpcUuid() {
        UUID raw = UUID.randomUUID();
        // Set the version bits to 2 — marks it as a non-real player to clients
        // that check the offline UUID layout. Avoids collisions with real players.
        long msb = (raw.getMostSignificantBits() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000002000L;
        return new UUID(msb, raw.getLeastSignificantBits());
    }

    private static final class RenderedNpc implements NpcHandle {
        private final int entityId;
        private final UUID profileUuid;
        private NpcDefinition definition;
        private final Set<UUID> viewers = new HashSet<>();

        private RenderedNpc(int entityId, NpcDefinition definition, UUID profileUuid) {
            this.entityId = entityId;
            this.definition = definition;
            this.profileUuid = profileUuid;
        }

        @Override
        public NpcId id() {
            return definition.id();
        }

        @Override
        public int entityId() {
            return entityId;
        }
    }
}
