package hex.towns.heart;

import hex.towns.model.Town;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TownHeartRenderer implements Listener {
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0, 0, 1, 0);

    /**
     * Model jest budowany wokol srodka bloku serca. Szerokosc i wysokosc bryly
     * mieszcza sie w okolicach 1.5 bloku, ale sklada sie z wielu mniejszych
     * BlockDisplay, dzieki czemu z daleka przypomina pulsujace anatomiczne serce.
     */
    private static final double MODEL_Y_OFFSET = 0.82;
    private static final double LABEL_Y_OFFSET = 2.48;
    private static final float INTERACTION_WIDTH = 2.05f;
    private static final float INTERACTION_HEIGHT = 2.45f;
    private static final Set<String> EXPECTED_PART_IDS = Set.of(
            "lobe_left_outer", "lobe_left_inner", "lobe_right_outer", "lobe_right_inner", "top_bridge",
            "body_left", "body_center", "body_right", "lower_left", "lower_right", "tip",
            "highlight_front_top", "highlight_front_mid", "highlight_front_low",
            "vein_aorta", "vein_aorta_cap", "vein_left_branch", "vein_right_branch",
            "vein_front_left", "vein_front_right", "vein_lower",
            "interaction", "label"
    );

    private final Plugin plugin;
    private final NamespacedKey townIdKey;
    private final NamespacedKey partKey;
    private final NamespacedKey standardTownKey;
    private final NamespacedKey objectTypeKey;
    private final NamespacedKey objectIdKey;
    private final Map<UUID, Set<UUID>> entitiesByTown = new ConcurrentHashMap<>();
    private final Map<UUID, String> partByEntity = new ConcurrentHashMap<>();
    private BukkitTask pulseTask;

    public TownHeartRenderer(Plugin plugin) {
        this.plugin = plugin;
        this.townIdKey = new NamespacedKey(plugin, "town_heart_visual_town");
        this.partKey = new NamespacedKey(plugin, "town_heart_visual_part");
        this.standardTownKey = new NamespacedKey(plugin, "town_uuid");
        this.objectTypeKey = new NamespacedKey(plugin, "object_type");
        this.objectIdKey = new NamespacedKey(plugin, "object_id");
    }

    public int registeredPartCount() {
        return partByEntity.size();
    }

    public void clearRegistry() {
        entitiesByTown.clear();
        partByEntity.clear();
    }

    public boolean hasAnyHeartMarker(Entity entity) {
        if (entity == null) return false;
        return rawTownId(entity) != null || partId(entity) != null;
    }

    public String rawTownId(Entity entity) {
        if (entity == null) return null;
        return entity.getPersistentDataContainer().get(townIdKey, PersistentDataType.STRING);
    }

    public String partId(Entity entity) {
        if (entity == null) return null;
        return entity.getPersistentDataContainer().get(partKey, PersistentDataType.STRING);
    }

    public boolean isKnownPart(String partId) {
        return partId != null && EXPECTED_PART_IDS.contains(partId);
    }

    public boolean matchesExpectedEntityType(Entity entity, String partId) {
        if (entity == null || partId == null) return false;
        if ("interaction".equals(partId)) return entity instanceof Interaction;
        if ("label".equals(partId)) return entity instanceof TextDisplay;
        return EXPECTED_PART_IDS.contains(partId) && entity instanceof BlockDisplay;
    }

    public Set<String> expectedPartIds() {
        return EXPECTED_PART_IDS;
    }

    public void registerExisting(UUID townId, Entity entity, String partId) {
        if (townId == null || entity == null || partId == null || partId.isBlank()) return;
        registerEntity(townId, entity, partId);
    }

    public void unregister(Entity entity) {
        if (entity == null) return;
        UUID entityId = entity.getUniqueId();
        partByEntity.remove(entityId);
        for (Set<UUID> ids : entitiesByTown.values()) ids.remove(entityId);
        entitiesByTown.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) unregister(entity);
    }

    public void startPulse() {
        stopPulse();
        final int[] phase = {0};
        pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            switch (phase[0]) {
                // Pierwsze uderzenie: lewa górna komora mocno, lewa dolna subtelniej.
                case 0 -> {
                    applyBeat(BeatGroup.UPPER_LEFT, 0.70f, 4);
                    applyBeat(BeatGroup.LOWER_LEFT, 0.85f, 4);
                }
                case 4 -> {
                    applyBeat(BeatGroup.UPPER_LEFT, 1.00f, 4);
                    applyBeat(BeatGroup.LOWER_LEFT, 1.00f, 4);
                }
                // 0.1 s przerwy, potem drugie uderzenie: prawa dolna mocno, prawa górna subtelniej.
                case 10 -> {
                    applyBeat(BeatGroup.LOWER_RIGHT, 0.70f, 4);
                    applyBeat(BeatGroup.UPPER_RIGHT, 0.85f, 4);
                }
                case 14 -> {
                    applyBeat(BeatGroup.LOWER_RIGHT, 1.00f, 4);
                    applyBeat(BeatGroup.UPPER_RIGHT, 1.00f, 4);
                }
                default -> {
                    // Reszta cyklu to pauza imitująca chwilę spoczynku serca.
                    // Nie robimy dodatkowego globalnego resetu, bo wyglądał jak trzecie, jednoczesne uderzenie.
                }
            }
            phase[0] = (phase[0] + 1) % 38;
        }, 20L, 1L);
    }

    private void applyBeat(BeatGroup group, float factor, int durationTicks) {
        for (Map.Entry<UUID, String> entry : new ArrayList<>(partByEntity.entrySet())) {
            if (beatGroup(entry.getValue()) != group) continue;
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof BlockDisplay display && display.isValid()) {
                applyScale(display, entry.getValue(), factor, durationTicks);
            } else {
                partByEntity.remove(entry.getKey());
            }
        }
    }

    private void applyScale(BlockDisplay display, String partId, float factor, int durationTicks) {
        Vector3f base = baseScale(partId);
        if (base == null) return;
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(1, durationTicks));
        display.setTransformation(new Transformation(new Vector3f(0, 0, 0), NO_ROTATION,
                new Vector3f(base.x * factor, base.y * factor, base.z * factor), NO_ROTATION));
    }

    public void stopPulse() {
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
    }

    public void render(Town town, TownHeartLocation heart) {
        Location blockCenter = heart.toLocation();
        if (blockCenter == null || blockCenter.getWorld() == null) return;
        remove(town.id(), blockCenter);
        blockCenter.getChunk().load(true);

        // TownHeartService zapisuje x/z jako (chunk << 4) + 8, a TownHeartLocation
        // oddaje Location z +0.5, wiec wizualnie serce stoi dokladnie w srodku
        // centralnego chunka, na srodku bloku bazowego.
        Location modelCenter = blockCenter.clone().add(0, MODEL_Y_OFFSET, 0);
        spawnHeartPieces(town, modelCenter);
        spawnInteractionHitbox(town, blockCenter.clone());
        spawnLabel(town, blockCenter.clone().add(0, LABEL_Y_OFFSET, 0));
    }

    public void remove(UUID townId) {
        remove(townId, null);
    }

    /** Removes only tracked entities or strict PDC matches near the known heart location. */
    public void remove(UUID townId, Location heartHint) {
        if (townId == null) return;
        Set<UUID> tracked = entitiesByTown.remove(townId);
        if (tracked != null) {
            for (UUID entityId : new ArrayList<>(tracked)) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) entity.remove();
                partByEntity.remove(entityId);
            }
        }
        if (heartHint == null || heartHint.getWorld() == null || !heartHint.getChunk().isLoaded()) return;
        String id = townId.toString();
        for (Entity entity : heartHint.getWorld().getNearbyEntities(heartHint, 4.0, 5.0, 4.0)) {
            String stored = entity.getPersistentDataContainer().get(townIdKey, PersistentDataType.STRING);
            String standard = entity.getPersistentDataContainer().get(standardTownKey, PersistentDataType.STRING);
            if (id.equals(stored) || id.equals(standard)) {
                partByEntity.remove(entity.getUniqueId());
                entity.remove();
            }
        }
    }

    private void spawnHeartPieces(Town town, Location center) {
        for (Piece piece : pieces()) {
            Location loc = center.clone().add(piece.dx() - piece.sx() / 2.0, piece.dy() - piece.sy() / 2.0, piece.dz() - piece.sz() / 2.0);
            center.getWorld().spawn(loc, BlockDisplay.class, entity -> {
                entity.setBlock(piece.data());
                entity.setPersistent(true);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setSilent(true);
                entity.setViewRange(96.0f);
                markOwned(entity, town, "heart_piece", piece.id());
                entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, piece.id());
                registerEntity(town.id(), entity, piece.id());
                entity.setInterpolationDelay(0);
                entity.setInterpolationDuration(1);
                entity.setTransformation(new Transformation(new Vector3f(0, 0, 0), NO_ROTATION, new Vector3f(piece.sx(), piece.sy(), piece.sz()), NO_ROTATION));
            });
        }
    }

    private void spawnInteractionHitbox(Town town, Location location) {
        location.getWorld().spawn(location, Interaction.class, entity -> {
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setResponsive(true);
            entity.setInteractionWidth(INTERACTION_WIDTH);
            entity.setInteractionHeight(INTERACTION_HEIGHT);
            markOwned(entity, town, "town_heart_visual", "interaction");
            entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, "interaction");
            registerEntity(town.id(), entity, "interaction");
        });
    }

    private void spawnLabel(Town town, Location location) {
        location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setViewRange(96.0f);
            entity.text(Component.text("Serce miasta ", NamedTextColor.RED).append(Component.text(town.name(), NamedTextColor.GOLD)));
            markOwned(entity, town, "town_heart_visual", "label");
            entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, "label");
            registerEntity(town.id(), entity, "label");
        });
    }

    private void markOwned(Entity entity, Town town, String type, String objectId) {
        String townId = town.id().toString();
        entity.getPersistentDataContainer().set(townIdKey, PersistentDataType.STRING, townId);
        entity.getPersistentDataContainer().set(standardTownKey, PersistentDataType.STRING, townId);
        entity.getPersistentDataContainer().set(objectTypeKey, PersistentDataType.STRING, type);
        entity.getPersistentDataContainer().set(objectIdKey, PersistentDataType.STRING, townId + ":" + objectId);
    }

    private void registerEntity(UUID townId, Entity entity, String partId) {
        entitiesByTown.computeIfAbsent(townId, ignored -> ConcurrentHashMap.newKeySet()).add(entity.getUniqueId());
        partByEntity.put(entity.getUniqueId(), partId);
    }

    private Vector3f baseScale(String id) {
        if (id == null) return null;
        for (Piece piece : pieces()) {
            if (piece.id().equals(id)) {
                return new Vector3f(piece.sx(), piece.sy(), piece.sz());
            }
        }
        return null;
    }

    private BeatGroup beatGroup(String id) {
        if (id == null) return BeatGroup.NONE;
        for (Piece piece : pieces()) {
            if (!piece.id().equals(id)) continue;
            if (piece.dx() < 0.0D && piece.dy() >= 0.0D) return BeatGroup.UPPER_LEFT;
            if (piece.dx() < 0.0D && piece.dy() < 0.0D) return BeatGroup.LOWER_LEFT;
            if (piece.dx() >= 0.0D && piece.dy() <= 0.05D) return BeatGroup.LOWER_RIGHT;
            if (piece.dx() >= 0.0D && piece.dy() > 0.05D) return BeatGroup.UPPER_RIGHT;
            return BeatGroup.NONE;
        }
        return BeatGroup.NONE;
    }

    private static List<Piece> pieces() {
        BlockData deepRed = Material.RED_CONCRETE.createBlockData();
        BlockData darkRed = Material.RED_TERRACOTTA.createBlockData();
        BlockData lightRed = Material.RED_GLAZED_TERRACOTTA.createBlockData();
        BlockData softRed = Material.PINK_CONCRETE.createBlockData();
        BlockData veinBlue = Material.BLUE_CONCRETE.createBlockData();
        BlockData veinLight = Material.LIGHT_BLUE_CONCRETE.createBlockData();
        BlockData veinDark = Material.BLUE_TERRACOTTA.createBlockData();

        return List.of(
                // Gorna, dwukomorowa czesc serca.
                new Piece("lobe_left_outer", "body", darkRed, -0.42, 0.36, 0.00, 0.42f, 0.46f, 0.46f),
                new Piece("lobe_left_inner", "body", deepRed, -0.22, 0.48, -0.02, 0.42f, 0.44f, 0.48f),
                new Piece("lobe_right_outer", "body", deepRed, 0.42, 0.36, 0.00, 0.42f, 0.46f, 0.46f),
                new Piece("lobe_right_inner", "body", lightRed, 0.20, 0.48, -0.02, 0.40f, 0.44f, 0.48f),
                new Piece("top_bridge", "body", softRed, 0.00, 0.33, -0.04, 0.48f, 0.32f, 0.44f),

                // Korpus, asymetryczny jak anatomiczne serce, nie idealna ikonka.
                new Piece("body_left", "body", darkRed, -0.25, 0.02, 0.02, 0.48f, 0.55f, 0.52f),
                new Piece("body_center", "body", deepRed, 0.06, -0.02, 0.00, 0.56f, 0.60f, 0.56f),
                new Piece("body_right", "body", lightRed, 0.32, 0.02, 0.02, 0.42f, 0.52f, 0.50f),
                new Piece("lower_left", "body", darkRed, -0.18, -0.38, 0.00, 0.42f, 0.46f, 0.46f),
                new Piece("lower_right", "body", deepRed, 0.20, -0.40, 0.00, 0.38f, 0.44f, 0.44f),
                new Piece("tip", "body", darkRed, 0.03, -0.72, 0.00, 0.30f, 0.34f, 0.34f),

                // Jasniejsze plamy, zeby model nie byl jednolita bryla czerwieni.
                new Piece("highlight_front_top", "body", softRed, -0.10, 0.23, -0.28, 0.18f, 0.24f, 0.10f),
                new Piece("highlight_front_mid", "body", lightRed, 0.23, -0.12, -0.30, 0.18f, 0.32f, 0.10f),
                new Piece("highlight_front_low", "body", softRed, -0.02, -0.48, -0.27, 0.16f, 0.20f, 0.10f),

                // Naczynia/zyly - niebieskie i jasnoniebieskie paski na froncie/gorze.
                new Piece("vein_aorta", "vein", veinBlue, 0.00, 0.86, -0.18, 0.16f, 0.46f, 0.14f),
                new Piece("vein_aorta_cap", "vein", veinLight, 0.00, 1.12, -0.18, 0.18f, 0.18f, 0.16f),
                new Piece("vein_left_branch", "vein", veinDark, -0.28, 0.78, -0.20, 0.12f, 0.34f, 0.12f),
                new Piece("vein_right_branch", "vein", veinLight, 0.32, 0.72, -0.20, 0.12f, 0.36f, 0.12f),
                new Piece("vein_front_left", "vein", veinBlue, -0.22, 0.10, -0.32, 0.08f, 0.48f, 0.08f),
                new Piece("vein_front_right", "vein", veinLight, 0.18, -0.08, -0.34, 0.08f, 0.44f, 0.08f),
                new Piece("vein_lower", "vein", veinDark, 0.02, -0.48, -0.31, 0.08f, 0.28f, 0.08f)
        );
    }

    private enum BeatGroup { UPPER_LEFT, LOWER_LEFT, LOWER_RIGHT, UPPER_RIGHT, NONE }

    private record Piece(String id, String part, BlockData data, double dx, double dy, double dz, float sx, float sy, float sz) {}
}
