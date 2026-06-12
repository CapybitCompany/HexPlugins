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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TownHeartRenderer {
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

    private final Plugin plugin;
    private final NamespacedKey townIdKey;
    private final NamespacedKey partKey;
    private BukkitTask pulseTask;
    private boolean pulse;

    public TownHeartRenderer(Plugin plugin) {
        this.plugin = plugin;
        this.townIdKey = new NamespacedKey(plugin, "town_heart_visual_town");
        this.partKey = new NamespacedKey(plugin, "town_heart_visual_part");
    }

    public void startPulse() {
        stopPulse();
        pulseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            pulse = !pulse;
            float factor = pulse ? 1.085f : 0.965f;
            float veinFactor = pulse ? 1.12f : 0.94f;
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (!(entity instanceof BlockDisplay display)) continue;
                    if (!display.getPersistentDataContainer().has(townIdKey, PersistentDataType.STRING)) continue;
                    String partId = display.getPersistentDataContainer().get(partKey, PersistentDataType.STRING);
                    Vector3f base = baseScale(partId);
                    if (base == null) continue;
                    float localFactor = isVeinPart(partId) ? veinFactor : factor;
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(14);
                    display.setTransformation(new Transformation(new Vector3f(0, 0, 0), NO_ROTATION,
                            new Vector3f(base.x * localFactor, base.y * localFactor, base.z * localFactor), NO_ROTATION));
                }
            }
        }, 20L, 14L);
    }

    public void stopPulse() {
        if (pulseTask != null) {
            pulseTask.cancel();
            pulseTask = null;
        }
    }

    public void render(Town town, TownHeartLocation heart) {
        remove(town.id());
        Location blockCenter = heart.toLocation();
        if (blockCenter == null || blockCenter.getWorld() == null) return;
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
        String id = townId.toString();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                String stored = entity.getPersistentDataContainer().get(townIdKey, PersistentDataType.STRING);
                if (id.equals(stored)) {
                    entity.remove();
                }
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
                entity.getPersistentDataContainer().set(townIdKey, PersistentDataType.STRING, town.id().toString());
                entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, piece.id());
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
            entity.getPersistentDataContainer().set(townIdKey, PersistentDataType.STRING, town.id().toString());
            entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, "interaction");
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
            entity.getPersistentDataContainer().set(townIdKey, PersistentDataType.STRING, town.id().toString());
            entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, "label");
        });
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

    private boolean isVeinPart(String id) {
        return id != null && id.startsWith("vein_");
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

    private record Piece(String id, String part, BlockData data, double dx, double dy, double dz, float sx, float sy, float sz) {}
}
