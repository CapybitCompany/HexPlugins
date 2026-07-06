package hexnpc.render.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import hexnpc.config.HexNpcConfig;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.render.NpcHandle;
import hexnpc.render.NpcRenderer;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

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

    // Basis-Entity-Metadata-Indizes. Anders als die "Displayed Skin Parts" (siehe
    // PlayerSkinLayersMetadata) liegen diese Felder auf der Basisklasse Entity und
    // sind ueber 1.20–1.21.11 stabil — die Avatar-Einfuegung in 1.21.9 hat nur die
    // Player-spezifischen Indizes darueber verschoben, nicht index 0/5/6.
    private static final int ENTITY_FLAGS_INDEX = 0;   // Byte: on-fire/sneak/…/glow
    static final int CUSTOM_NAME_INDEX = 2;            // Optional<Component> (package-private: Test-Seam)
    static final int CUSTOM_NAME_VISIBLE_INDEX = 3;    // Boolean (package-private: Test-Seam)
    private static final int NO_GRAVITY_INDEX = 5;     // Boolean
    private static final int POSE_INDEX = 6;           // EntityPose

    private static final byte FLAG_SNEAKING = 0x02;
    private static final byte FLAG_SWIMMING = 0x10;
    private static final byte FLAG_INVISIBLE = 0x20;
    private static final byte FLAG_GLOWING = 0x40;

    /**
     * Hoehe des unsichtbaren Nameplate-Hologramms ueber den NPC-Fuessen. Der Armor-Stand
     * rendert seinen Namen ~2.25 Bloecke ueber seinen Fuessen — bei Offset 0 schwebt der
     * Name also knapp ueber dem Kopf eines 1.8 hohen Spielers (kosmetisch, justierbar).
     */
    private static final double NAMEPLATE_Y_OFFSET = 0.0D;

    private final Plugin plugin;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Logger logger;

    private final Map<NpcId, RenderedNpc> bySupervisor = new HashMap<>();
    private final Map<Integer, NpcId> byEntityId = new HashMap<>();
    private BukkitTask refreshTask;

    /**
     * Vertikaler Render-Offset der Sitz-Pose (Config {@code render.sitting-y-offset},
     * Default {@code -1.0}). Nur render-/packet-seitig: die gespeicherte NPC-Location
     * bleibt unveraendert.
     */
    private double sittingYOffset() {
        HexNpcConfig config = configSupplier.get();
        if (config == null) {
            return HexNpcConfig.Render.DEFAULT_SITTING_Y_OFFSET;
        }
        return config.render().sittingYOffset();
    }

    /** Reine, testbare Ableitung der Sitz-/Mount-Y aus Basis-Y und Offset. */
    static double seatMountY(double baseY, double offset) {
        return baseY + offset;
    }

    public PacketNpcRenderer(Plugin plugin,
                             Supplier<HexNpcConfig> configSupplier,
                             Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void start() {
        // Visibility refresh: pick up players that walked into / out of range.
        // The click packet listener itself is registered once by HexNpcPlugin.
        if (refreshTask != null) {
            return;
        }
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshVisibility, 20L, 10L);
    }

    @Override
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        for (RenderedNpc rendered : new ArrayList<>(bySupervisor.values())) {
            for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    sendDestroy(viewer, rendered);
                }
            }
        }
        bySupervisor.clear();
        byEntityId.clear();
    }

    private synchronized void refreshVisibility() {
        HexNpcConfig config = configSupplier.get();
        if (config == null) {
            return;
        }
        if (!config.enabled()) {
            // While disabled, all NPCs must be hidden from every viewer.
            for (RenderedNpc rendered : bySupervisor.values()) {
                for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null) {
                        sendDestroy(viewer, rendered);
                    }
                    rendered.viewers.remove(viewerId);
                }
            }
            return;
        }
        double radius = config.render().viewDistanceBlocks();
        for (RenderedNpc rendered : bySupervisor.values()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                boolean visible = rendered.viewers.contains(player.getUniqueId());
                boolean shouldBeVisible = inSameWorld(player, rendered) && inRange(player, rendered, radius);
                if (visible && !shouldBeVisible) {
                    rendered.viewers.remove(player.getUniqueId());
                    sendDestroy(player, rendered);
                } else if (!visible && shouldBeVisible) {
                    renderFor(player, rendered);
                }
            }
        }
    }

    @Override
    public synchronized NpcHandle spawn(NpcDefinition definition) {
        RenderedNpc existing = bySupervisor.get(definition.id());
        if (existing != null) {
            existing.definition = definition;
            return existing;
        }
        // Always allocate the handle so reverse-lookup / refresh works when
        // the plugin is re-enabled later. Only send packets if currently enabled.
        int npcEntityId = ENTITY_ID.getAndIncrement();
        int seatEntityId = ENTITY_ID.getAndIncrement();
        int nameEntityId = ENTITY_ID.getAndIncrement();
        UUID profileUuid = randomNpcUuid();
        // Technischer Profilname/Team-Entry einmal pro Render bestimmen und gegen aktuell
        // online Spielernamen absichern (siehe uniqueProfileName / buildTechnicalProfileName).
        RenderedNpc rendered = new RenderedNpc(npcEntityId, seatEntityId, nameEntityId,
                definition, profileUuid, uniqueProfileName(profileUuid));
        bySupervisor.put(definition.id(), rendered);
        // Alle drei IDs auf dieselbe NpcId mappen, damit jeder Klick — auf die Player-
        // Entity, das Sitz-Vehicle (beim Sitzen) oder das Nameplate-Hologramm — dieselbe
        // Interaktion ausloest. Seat/Name werden nur bei Bedarf tatsaechlich gespawnt;
        // ein Mapping auf eine nie gespawnte Id ist unschaedlich.
        byEntityId.put(rendered.entityId, definition.id());
        byEntityId.put(rendered.seatEntityId, definition.id());
        byEntityId.put(rendered.nameEntityId, definition.id());

        HexNpcConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return rendered;
        }
        double radius = config.render().viewDistanceBlocks();
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
        byEntityId.remove(rendered.seatEntityId);
        byEntityId.remove(rendered.nameEntityId);
        for (UUID viewerId : new ArrayList<>(rendered.viewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendDestroy(viewer, rendered);
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
                sendDestroy(viewer, rendered);
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
        HexNpcConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return;
        }
        double radius = config.render().viewDistanceBlocks();
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
                sendDestroy(player, rendered);
            }
        }
    }

    @Override
    public synchronized Optional<NpcHandle> handle(NpcId id) {
        RenderedNpc rendered = bySupervisor.get(id);
        return rendered == null ? Optional.empty() : Optional.of(rendered);
    }

    /**
     * Lookup vom Netty-Thread (PacketEvents-Listener). Die Map wird im Main-Thread
     * von spawn/despawn mutiert; HashMap ist nicht thread-safe (Resize-Race),
     * deshalb synchronisieren wir auf demselben Monitor wie die Mutationen.
     */
    @Override
    public synchronized Optional<NpcId> lookupByEntityId(int entityId) {
        return Optional.ofNullable(byEntityId.get(entityId));
    }

    /**
     * Test-Hook: reservierte Seat-Entity-Id eines gespawnten NPCs (fuer die
     * Sitz-Pose) oder {@link Optional#empty()}, wenn kein NPC bekannt ist. Nur
     * package-private, damit die oeffentliche Renderer-API sauber bleibt.
     */
    synchronized Optional<Integer> seatEntityId(NpcId id) {
        RenderedNpc rendered = bySupervisor.get(id);
        return rendered == null ? Optional.empty() : Optional.of(rendered.seatEntityId);
    }

    /**
     * Test-Hook: reservierte Nameplate-Hologramm-Entity-Id eines gespawnten NPCs oder
     * {@link Optional#empty()}, wenn kein NPC bekannt ist. Nur package-private.
     */
    synchronized Optional<Integer> nameEntityId(NpcId id) {
        RenderedNpc rendered = bySupervisor.get(id);
        return rendered == null ? Optional.empty() : Optional.of(rendered.nameEntityId);
    }

    private void renderFor(Player player, RenderedNpc rendered) {
        try {
            sendPlayerInfoAdd(player, rendered);
            sendSpawn(player, rendered);
            sendRotation(player, rendered);
            sendSkinLayers(player, rendered.entityId);
            // Glow + Pose (Basis-Entity-Metadata, stabile Indizes).
            sendAppearanceMetadata(player, rendered);
            // Sichtbaren Nickname als echte Nameplate ueber dem NPC anzeigen: die
            // Profil-Nameplate (technische Id) wird per Team ausgeblendet, der Nickname
            // kommt aus einem unsichtbaren Hologramm mit Custom Name.
            sendNameplate(player, rendered);
            // Sitzen wird ueber ein unsichtbares Reit-Entity realisiert.
            if (rendered.definition.appearance().pose() == NpcPose.SITTING) {
                sendSeat(player, rendered);
            }
            rendered.viewers.add(player.getUniqueId());
            // Kein verzoegertes PlayerInfoRemove mehr beim Rendern: der Eintrag wird per
            // listed=false bereits nicht im Tab gelistet (siehe sendPlayerInfoAdd), und das
            // fruehere Delay-Task war die Ursache fuer Races mit neu gespawnten NPCs.
            // Aufgeraeumt wird der Profil-Eintrag stattdessen in sendDestroy (hide/despawn/stop).
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                    "HexNPC: failed to render NPC " + rendered.definition.id() + " for " + player.getName(), ex);
        }
    }

    private void sendPlayerInfoAdd(Player viewer, RenderedNpc rendered) {
        NpcDefinition def = rendered.definition;
        // UserProfile.name = Mojang-Username: max 16 Zeichen, sonst lehnt der Client
        // den Player-Info-Eintrag ab und der NPC erscheint ohne Skin. Der sichtbar
        // angezeigte Display-Name (Component) bleibt davon unberührt. Der Name ist ein
        // garantiert eindeutiger technischer Eintrag (siehe RenderedNpc.profileName) —
        // damit kann er nie mit echten Spielern oder anderen NPCs kollidieren und ist
        // zugleich der Scoreboard-Team-Entry fuer Nametag-Hiding und Glow-Farbe.
        UserProfile profile = new UserProfile(rendered.profileUuid, rendered.profileName());
        if (def.skin().hasTexture()) {
            profile.setTextureProperties(List.of(
                    new TextureProperty("textures", def.skin().value(), def.skin().signature())
            ));
        }
        // listed=false: Der Eintrag ist noetig, damit der Client den Skin aufloest, wird
        // aber nie in der Tab-Liste angezeigt. UPDATE_LISTED muss mitgeschickt werden,
        // damit der Client das listed-Flag ueberhaupt auswertet.
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, false, 0, GameMode.CREATIVE, displayComponent(def), null
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
        sendLookRotation(viewer, rendered.entityId, loc.yaw(), loc.pitch());
    }

    /**
     * Sendet Body-Rotation (EntityRotation) + Head-Look an einen Viewer. Gemeinsame Basis
     * fuer die Standard-Rotation und das Look-At-Feature. Rein packet-seitig — die
     * gespeicherte NPC-Location bleibt unberuehrt.
     */
    private void sendLookRotation(Player viewer, int entityId, float yaw, float pitch) {
        WrapperPlayServerEntityRotation rotation = new WrapperPlayServerEntityRotation(
                entityId, yaw, pitch, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, rotation);

        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(
                entityId, yaw);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
    }

    /**
     * Look-At fuer alle Viewer: dreht die Player-Entity (auch bei SITTING — der Head-Look
     * wirkt weiterhin) temporaer auf yaw/pitch. Seat-/Nameplate-Entities und die
     * Click-Lookup-Maps bleiben unberuehrt.
     */
    @Override
    public synchronized void lookAt(NpcId id, float yaw, float pitch) {
        RenderedNpc rendered = bySupervisor.get(id);
        if (rendered == null) {
            return;
        }
        for (UUID viewerId : rendered.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendLookRotation(viewer, rendered.entityId, yaw, pitch);
            }
        }
    }

    @Override
    public synchronized void resetLook(NpcId id) {
        RenderedNpc rendered = bySupervisor.get(id);
        if (rendered == null) {
            return;
        }
        NpcLocation loc = rendered.definition.location();
        for (UUID viewerId : rendered.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendLookRotation(viewer, rendered.entityId, loc.yaw(), loc.pitch());
            }
        }
    }

    private void sendTeleport(Player viewer, RenderedNpc rendered) {
        NpcLocation loc = rendered.definition.location();
        boolean sitting = rendered.definition.appearance().pose() == NpcPose.SITTING;
        // Beim Sitzen sitzt der NPC als Passagier auf dem Reit-Entity — der Client
        // positioniert Passagiere relativ zum Fahrzeug, also muss das Reit-Entity
        // teleportiert werden, nicht die NPC-Entity selbst.
        int targetId = sitting ? rendered.seatEntityId : rendered.entityId;
        double targetY = sitting ? seatMountY(loc.y(), sittingYOffset()) : loc.y();
        WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(
                targetId,
                new Vector3d(loc.x(), targetY, loc.z()),
                loc.yaw(),
                loc.pitch(),
                true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teleport);

        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(
                rendered.entityId, loc.yaw());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);

        // Nameplate-Hologramm folgt dem NPC.
        WrapperPlayServerEntityTeleport nameTeleport = new WrapperPlayServerEntityTeleport(
                rendered.nameEntityId,
                new Vector3d(loc.x(), loc.y() + NAMEPLATE_Y_OFFSET, loc.z()),
                0f, 0f, true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, nameTeleport);
    }

    private void sendSkinLayers(Player viewer, int entityId) {
        // Indeks metadanych "Displayed Skin Parts" zmienia sie miedzy wersjami Minecrafta
        // (vide 1.21.9 - wstawiona klasa Avatar). Stary kod twardo wysylal indeks 17, co
        // od 1.21.9 jest polem Float (Additional Hearts) i powoduje natychmiastowy
        // disconnect klienta. Pelne uzasadnienie: zobacz PlayerSkinLayersMetadata.
        Optional<Integer> index = resolveSkinLayersIndex(Bukkit.getServer().getMinecraftVersion());
        if (index.isEmpty()) {
            return;
        }
        EntityData data = new EntityData(index.get(), EntityDataTypes.BYTE,
                PlayerSkinLayersMetadata.ALL_LAYERS_MASK);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                entityId, List.of(data));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    /**
     * Sendet Glow + Pose als Basis-Entity-Metadata (Flags-Byte index 0 + Pose index 6).
     * Diese Felder liegen auf der Basisklasse Entity und sind — anders als die
     * "Displayed Skin Parts" — versionsstabil ueber 1.20–1.21.11.
     */
    private void sendAppearanceMetadata(Player viewer, RenderedNpc rendered) {
        NpcAppearance appearance = rendered.definition.appearance();
        List<EntityData> data = List.of(
                new EntityData(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, entityFlags(appearance)),
                new EntityData(POSE_INDEX, EntityDataTypes.ENTITY_POSE, poseFor(appearance.pose()))
        );
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                rendered.entityId, data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    /**
     * Baut das Entity-Flags-Byte (index 0). Glow ist Bit 0x40. Fuer die geduckte bzw.
     * kriechende Pose wird zusaetzlich das passende Bewegungs-Bit gesetzt, damit der
     * Client die Haltung sauber rendert.
     */
    static byte entityFlags(NpcAppearance appearance) {
        int flags = 0;
        if (appearance.glow()) {
            flags |= FLAG_GLOWING;
        }
        switch (appearance.pose()) {
            case SNEAKING -> flags |= FLAG_SNEAKING;
            case CRAWLING -> flags |= FLAG_SWIMMING;
            default -> {
                // STANDING/SITTING/SLEEPING brauchen keine Bewegungs-Flags.
            }
        }
        return (byte) flags;
    }

    /**
     * Abbildung der NPC-Pose auf die PacketEvents-{@link EntityPose}. SITTING wird ueber
     * ein Reit-Entity realisiert; die Player-Entity selbst bleibt dabei STANDING.
     */
    static EntityPose poseFor(NpcPose pose) {
        return switch (pose) {
            case STANDING, SITTING -> EntityPose.STANDING;
            case SLEEPING -> EntityPose.SLEEPING;
            case CRAWLING -> EntityPose.SWIMMING;
            case SNEAKING -> EntityPose.CROUCHING;
        };
    }

    /**
     * Spawnt das unsichtbare Reit-Entity (Armor Stand) und setzt den NPC als Passagier,
     * wodurch der Client den NPC in Sitzhaltung darstellt. Nutzt ausschliesslich
     * versionsstabile Basis-Entity-Metadata (unsichtbar via Flags 0x20, No-Gravity index 5).
     */
    private void sendSeat(Player viewer, RenderedNpc rendered) {
        NpcLocation loc = rendered.definition.location();
        WrapperPlayServerSpawnEntity seatSpawn = new WrapperPlayServerSpawnEntity(
                rendered.seatEntityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.ARMOR_STAND,
                new Vector3d(loc.x(), seatMountY(loc.y(), sittingYOffset()), loc.z()),
                0f, 0f, 0f, 0, Optional.empty()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, seatSpawn);

        List<EntityData> seatMeta = List.of(
                new EntityData(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                new EntityData(NO_GRAVITY_INDEX, EntityDataTypes.BOOLEAN, Boolean.TRUE)
        );
        WrapperPlayServerEntityMetadata seatMetaPacket = new WrapperPlayServerEntityMetadata(
                rendered.seatEntityId, seatMeta);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, seatMetaPacket);

        WrapperPlayServerSetPassengers passengers = new WrapperPlayServerSetPassengers(
                rendered.seatEntityId, new int[]{rendered.entityId});
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, passengers);
    }

    /**
     * Zeigt den sichtbaren Nickname als echte Nameplate ueber dem NPC.
     *
     * <p>Player-Entities rendern ihren Nametag aus dem Spielerprofil bzw. Team — NICHT
     * aus der CustomName-Metadata. Deshalb genuegt {@code PlayerInfo.displayName} nicht.
     * Wir kombinieren daher zwei Schritte:
     * <ol>
     *   <li>Ein Scoreboard-Team mit {@link WrapperPlayServerTeams.NameTagVisibility#NEVER}
     *       blendet den technischen Profil-Nametag (die 16-Zeichen-Id) aus.</li>
     *   <li>Ein unsichtbares Hologramm-Entity (Armor Stand) mit Custom Name + Custom Name
     *       Visible traegt den farbigen Nickname — nur ueber versionsstabile Basis-Entity-
     *       Metadata (index 2/3/0/5).</li>
     * </ol>
     * Das Hologramm ist zusaetzlich in {@code byEntityId} gemappt, damit ein Klick auf den
     * Namen dieselbe Interaktion wie ein Klick auf den Koerper ausloest.
     */
    private void sendNameplate(Player viewer, RenderedNpc rendered) {
        // 1) Profil-Nametag (technischer Entry) per Team ausblenden UND die Glow-Farbe
        //    setzen. Die Team-Farbe steuert die Glow-Farbe der NPC-Body-Entity; der
        //    Nametag selbst ist NEVER sichtbar, also faerbt sie NICHT die Nameplate.
        //    Die Nameplate-Farbe kommt unabhaengig aus den &-Codes (eigenes Hologramm).
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                glowTeamColor(rendered.definition.appearance()),
                WrapperPlayServerTeams.OptionData.NONE
        );
        // Team-Entry = der eindeutige technische Profilname der NPC-Body-Entity, damit
        // Team-Formatierung/Glow-Farbe genau diese Entity treffen (Player-Entities werden
        // per Username, nicht per UUID, einem Team zugeordnet).
        WrapperPlayServerTeams team = new WrapperPlayServerTeams(
                rendered.teamName(), WrapperPlayServerTeams.TeamMode.CREATE, info,
                rendered.profileName());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, team);

        // 2) Unsichtbares Hologramm mit dem Nickname als Custom Name.
        NpcLocation loc = rendered.definition.location();
        WrapperPlayServerSpawnEntity nameSpawn = new WrapperPlayServerSpawnEntity(
                rendered.nameEntityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.ARMOR_STAND,
                new Vector3d(loc.x(), loc.y() + NAMEPLATE_Y_OFFSET, loc.z()),
                0f, 0f, 0f, 0, Optional.empty()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, nameSpawn);

        WrapperPlayServerEntityMetadata nameMeta = new WrapperPlayServerEntityMetadata(
                rendered.nameEntityId, nameplateMetadata(rendered.definition));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, nameMeta);
    }

    /**
     * Inhalt der Nameplate: der sichtbare Nickname als Adventure-{@link Component} (Legacy-
     * Farben, Id-Fallback). Package-private Test-Seam fuer den echten Nameplate-Pfad —
     * bewusst getrennt vom {@code PlayerInfo.displayName}, das fuer Player-Entities nicht
     * als Nametag wirkt. Identisch zu {@link #displayComponent(NpcDefinition)}, aber als
     * eigener Einstiegspunkt der Nameplate-Metadata benannt.
     */
    static Component nameplateComponent(NpcDefinition def) {
        return displayComponent(def);
    }

    /**
     * Metadata des Nameplate-Hologramms: Custom Name (index 2, {@link #nameplateComponent})
     * + Custom Name Visible (index 3) sowie unsichtbar (flags 0x20) und schwerelos (index 5).
     * Alle Indizes liegen auf der Basisklasse Entity und sind versionsstabil.
     */
    private List<EntityData> nameplateMetadata(NpcDefinition def) {
        return List.of(
                new EntityData(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                        Optional.of(nameplateComponent(def))),
                new EntityData(CUSTOM_NAME_VISIBLE_INDEX, EntityDataTypes.BOOLEAN, Boolean.TRUE),
                new EntityData(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                new EntityData(NO_GRAVITY_INDEX, EntityDataTypes.BOOLEAN, Boolean.TRUE)
        );
    }

    /**
     * Test-friendly hook: delegacja do {@link PlayerSkinLayersMetadata}. Trzymamy
     * tu cienka warstwe (zwracamy sam indeks, nie {@code EntityData}) zeby testy
     * jednostkowe mogly weryfikowac wybor wersji bez inicjalizacji PacketEvents.
     */
    static Optional<Integer> resolveSkinLayersIndex(String minecraftVersion) {
        return PlayerSkinLayersMetadata.resolve(minecraftVersion);
    }

    private void sendDestroy(Player viewer, RenderedNpc rendered) {
        // Entity(s) entfernen — inklusive Sitz-Mount und Nameplate-Hologramm, falls vorhanden.
        // Ein Destroy fuer eine nie gespawnte Id ist fuer den Client folgenlos.
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(
                rendered.entityId, rendered.seatEntityId, rendered.nameEntityId);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        // Scoreboard-Team des NPCs wieder abbauen, damit kein verwaister Team-Eintrag bleibt.
        WrapperPlayServerTeams teamRemove = new WrapperPlayServerTeams(
                rendered.teamName(), WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, teamRemove);
        // WICHTIG: auch den PlayerInfo-Eintrag entfernen. Selbst mit listed=false darf
        // beim hide/despawn/stop kein verwaister Profil-Eintrag zurueckbleiben.
        sendPlayerInfoRemove(viewer, rendered.profileUuid);
    }

    /**
     * Sichtbarer NPC-Nickname als String (roh, inkl. Legacy-Codes). Kommt aus
     * {@link NpcAppearance#displayName()} und faellt auf die NPC-Id zurueck.
     * Bewusst unabhaengig von {@link NpcSkin} — ein Skin-Wechsel aendert den
     * sichtbaren Namen nicht.
     */
    static String visibleName(NpcDefinition def) {
        NpcAppearance appearance = def.appearance();
        if (appearance.hasDisplayName()) {
            return appearance.displayName();
        }
        return def.id().value();
    }

    /**
     * Sichtbarer Nickname als Adventure-{@link Component}, ueber die bestehende
     * {@link LegacyFormat}-Utility geparst (unterstuetzt {@code &6}, {@code &l}, …).
     */
    static Component displayComponent(NpcDefinition def) {
        return LegacyFormat.component(visibleName(def));
    }

    /**
     * Technischer Mojang-Username fuer {@link UserProfile} UND Scoreboard-Team-Entry.
     * Aus den Zufallsbits der Render-{@code profileUuid} abgeleitet (plus Salt) — bewusst
     * NICHT aus NpcId/Skin/Nickname:
     * <ul>
     *   <li>Stabil fuer die Lebensdauer eines Renders und ein gueltiger Username
     *       (≤ 16 Zeichen, nur {@code [A-Za-z0-9_]}).</li>
     *   <li>Aus ~63 Zufallsbits gebildet: extrem geringe Kollisionswahrscheinlichkeit —
     *       weder mit anderen NPCs noch mit (auch cracked) Spielernamen. Garantiert
     *       kollisionsfrei ist ein 16-Zeichen-Name grundsaetzlich nicht, deshalb wird beim
     *       Spawn zusaetzlich gegen die aktuell online Spielernamen geprueft
     *       (siehe {@link #uniqueProfileName(UUID)}).</li>
     *   <li>Unabhaengig vom Skin — ein Skin-Wechsel aendert weder Profil- noch
     *       Nameplate-Namen (bestehender Fix bleibt erhalten).</li>
     * </ul>
     * Der sichtbare Nickname (Component) bleibt davon unberuehrt — siehe
     * {@link #visibleName(NpcDefinition)}.
     */
    static String buildTechnicalProfileName(UUID source, int salt) {
        long bits = (source.getMostSignificantBits() ^ source.getLeastSignificantBits())
                + ((long) salt * 0x9E3779B97F4A7C15L);
        // Vorzeichenfrei in Base36 -> nur [0-9a-z]; Praefix macht den Eintrag als NPC erkennbar.
        String suffix = Long.toString(bits & 0x7FFFFFFFFFFFFFFFL, 36);
        String name = "npc_" + suffix;
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    /**
     * Waehlt einen technischen Profilnamen, der nicht mit einem aktuell online Spieler
     * kollidiert. Variiert bei (extrem seltener) Kollision den Salt; nach einigen Versuchen
     * wird der Basisname akzeptiert (Restrisiko vernachlaessigbar, da aus Zufallsbits).
     */
    private String uniqueProfileName(UUID profileUuid) {
        for (int salt = 0; salt < 16; salt++) {
            String candidate = buildTechnicalProfileName(profileUuid, salt);
            if (!isOnlinePlayerName(candidate)) {
                return candidate;
            }
        }
        return buildTechnicalProfileName(profileUuid, 0);
    }

    private boolean isOnlinePlayerName(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Team-/Glow-Farbe eines NPCs. Nur wirksam wenn Glow aktiv ist und eine gueltige
     * Farbe gesetzt wurde; sonst {@link NamedTextColor#WHITE} (Standard-Glow, weisse
     * Team-Farbe). Package-private Test-Seam.
     */
    static NamedTextColor glowTeamColor(NpcAppearance appearance) {
        if (!appearance.glow() || appearance.glowColor() == null) {
            return NamedTextColor.WHITE;
        }
        NamedTextColor color = NamedTextColor.NAMES.value(appearance.glowColor());
        return color == null ? NamedTextColor.WHITE : color;
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
        /** Unsichtbares Reit-Entity fuer die Sitz-Pose; nur genutzt wenn Pose == SITTING. */
        private final int seatEntityId;
        /** Unsichtbares Hologramm-Entity, das den sichtbaren Nickname als Nameplate traegt. */
        private final int nameEntityId;
        private final UUID profileUuid;
        /** Stabiler, kollisionsgepruefter technischer Profilname/Team-Entry (siehe uniqueProfileName). */
        private final String profileName;
        private NpcDefinition definition;
        private final Set<UUID> viewers = new HashSet<>();

        private RenderedNpc(int entityId, int seatEntityId, int nameEntityId,
                            NpcDefinition definition, UUID profileUuid, String profileName) {
            this.entityId = entityId;
            this.seatEntityId = seatEntityId;
            this.nameEntityId = nameEntityId;
            this.definition = definition;
            this.profileUuid = profileUuid;
            this.profileName = profileName;
        }

        /**
         * Eindeutiger Scoreboard-Team-Name pro NPC-Render. Ueber die (global eindeutige)
         * Entity-Id gebildet, damit ein CREATE/REMOVE nie fremde NPC-Teams beeinflusst.
         */
        private String teamName() {
            return "hxnpc_" + entityId;
        }

        /**
         * Stabiler technischer Profilname/Team-Entry dieses Renders. Siehe
         * {@link PacketNpcRenderer#buildTechnicalProfileName(UUID, int)}.
         */
        private String profileName() {
            return profileName;
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
