package hex.parkour.service;

import hex.parkour.model.ParkourArena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Set;

public final class TerrainValidationService {
    private static final Set<Material> AIR = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);
    private static final Set<Material> BODY_MEDIA = Set.of(
            Material.WATER,
            Material.LADDER,
            Material.COBWEB,
            Material.CACTUS,
            Material.TRIPWIRE,
            Material.WEEPING_VINES,
            Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES,
            Material.TWISTING_VINES_PLANT,
            Material.VINE
    );

    public ValidationResult validate(Player player, ParkourArena arena) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null && arena.allowedVehicles().contains(vehicle.getType())) return ValidationResult.ok();

        Material body = bodyMedium(player);
        if (body != null && allowedMaterial(arena, body)) {
            return ValidationResult.ok();
        }

        if (!player.isOnGround()) return ValidationResult.ok();

        Material support = supportMaterial(player);
        if (support == null || AIR.contains(support)) return ValidationResult.ok();
        return allowedMaterial(arena, support)
                ? ValidationResult.ok()
                : ValidationResult.reset("unsupported support " + support);
    }

    private Material bodyMedium(Player player) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return null;
        int blockX = base.getBlockX();
        int blockZ = base.getBlockZ();
        int minY = (int) Math.floor(player.getBoundingBox().getMinY());
        int maxY = (int) Math.floor(player.getBoundingBox().getMaxY());
        for (int y = minY; y <= maxY; y++) {
            Material type = world.getBlockAt(blockX, y, blockZ).getType();
            if (BODY_MEDIA.contains(type)) return type;
        }
        return null;
    }

    private Material supportMaterial(Player player) {
        World world = player.getWorld();
        BoundingBox box = player.getBoundingBox();
        int y = (int) Math.floor(box.getMinY() - 0.01);
        double centerX = (box.getMinX() + box.getMaxX()) / 2.0;
        double centerZ = (box.getMinZ() + box.getMaxZ()) / 2.0;
        Block block = world.getBlockAt((int) Math.floor(centerX), y, (int) Math.floor(centerZ));
        return block.getType();
    }

    private boolean allowedMaterial(ParkourArena arena, Material material) {
        if (arena.allowedMaterials().contains(material)) return true;
        Material equivalent = configuredEquivalent(material);
        return equivalent != material && arena.allowedMaterials().contains(equivalent);
    }

    private Material configuredEquivalent(Material material) {
        return switch (material) {
            case TRIPWIRE -> Material.STRING;
            case WEEPING_VINES_PLANT -> Material.WEEPING_VINES;
            case TWISTING_VINES_PLANT -> Material.TWISTING_VINES;
            default -> material;
        };
    }

    public record ValidationResult(boolean reset, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(false, "");
        }

        public static ValidationResult reset(String reason) {
            return new ValidationResult(true, reason);
        }
    }
}
