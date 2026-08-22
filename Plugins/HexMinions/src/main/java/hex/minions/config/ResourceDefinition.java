package hex.minions.config;

import org.bukkit.Material;

import java.util.List;

public record ResourceDefinition(
        String id,
        String displayName,
        Material material,
        int customModelData,
        String collectionId,
        double worth,
        int stackSize,
        List<String> tags,
        boolean compressionEnabled,
        boolean blockConvertible,
        Material compressedMaterial
) {
}

