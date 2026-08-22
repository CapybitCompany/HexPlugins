package hex.minions.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the inventory carrier for resource-backed special items.
 * Compression is intentionally single-source: its carrier always comes from
 * resources.&lt;raw&gt;.compression.compressed-material, while legacy resource aliases
 * may still provide CMD/name/worth metadata.
 */
public final class SpecialItemCarrierResolver {
    private SpecialItemCarrierResolver() { }

    public static Optional<String> compressionSourceResourceId(String specialItemId, ConfigurationSection resourcesRoot) {
        if (resourcesRoot == null || specialItemId == null) return Optional.empty();
        String id = specialItemId.trim().toLowerCase(Locale.ROOT);
        String rawId;
        if (id.startsWith("super_compressed_")) rawId = id.substring("super_compressed_".length());
        else if (id.startsWith("compressed_")) rawId = id.substring("compressed_".length());
        else return Optional.empty();
        if (rawId.isBlank()) return Optional.empty();
        ConfigurationSection raw = resourcesRoot.getConfigurationSection(rawId);
        if (raw == null || !compressionEnabled(rawId, raw)) return Optional.empty();
        return Optional.of(rawId);
    }

    public static Optional<Material> resolveConfiguredCarrier(String specialItemId, String resourceRef, ConfigurationSection resourcesRoot) {
        if (resourcesRoot == null) return Optional.empty();
        Optional<String> compressionSource = compressionSourceResourceId(specialItemId, resourcesRoot);
        if (compressionSource.isPresent()) {
            ConfigurationSection raw = resourcesRoot.getConfigurationSection(compressionSource.get());
            if (raw == null) return Optional.empty();
            Material rawMaterial = Material.matchMaterial(raw.getString("material", ""));
            return Optional.ofNullable(compressionCarrier(compressionSource.get(), raw, rawMaterial));
        }
        String ref = resourceRef == null ? "" : resourceRef.trim().toLowerCase(Locale.ROOT);
        if (ref.isBlank()) return Optional.empty();
        ConfigurationSection resource = resourcesRoot.getConfigurationSection(ref);
        if (resource == null) return Optional.empty();
        return Optional.ofNullable(Material.matchMaterial(resource.getString("material", "")));
    }

    /**
     * Runtime compatibility for resource configs created before emerald compression and the
     * vanilla-block compression icon pass. Keeping this here makes existing server data folders
     * pick up the corrected behavior without requiring admins to delete resources.yml.
     */
    public static boolean compressionEnabled(String resourceId, ConfigurationSection resource) {
        if (resource == null) return false;
        return "emerald".equalsIgnoreCase(resourceId) || resource.getBoolean("compression.enabled", false);
    }

    /** Canonical inventory icon carrier for generated compressed/super-compressed resources. */
    public static Material compressionCarrier(String resourceId, ConfigurationSection resource, Material rawMaterial) {
        Material fallback = rawMaterial == null ? Material.STONE : rawMaterial;
        String id = resourceId == null ? "" : resourceId.trim().toLowerCase(Locale.ROOT);
        Material canonical = switch (id) {
            case "diamond" -> Material.DIAMOND_BLOCK;
            case "redstone" -> Material.REDSTONE_BLOCK;
            case "iron" -> Material.IRON_BLOCK;
            case "gold" -> Material.GOLD_BLOCK;
            case "copper" -> Material.RAW_COPPER_BLOCK;
            case "emerald" -> Material.EMERALD_BLOCK;
            default -> null;
        };
        if (canonical != null) return canonical;
        Material configured = resource == null ? null : Material.matchMaterial(resource.getString("compression.compressed-material", fallback.name()));
        return configured == null ? fallback : configured;
    }

    public static Material resolveCarrierOrFallback(String specialItemId, String resourceRef, ConfigurationSection resourcesRoot, Material fallback) {
        return resolveConfiguredCarrier(specialItemId, resourceRef, resourcesRoot).orElse(fallback);
    }
}
