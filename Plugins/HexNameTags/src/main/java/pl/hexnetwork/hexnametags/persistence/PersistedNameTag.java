package pl.hexnetwork.hexnametags.persistence;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.UUID;

public record PersistedNameTag(
        UUID targetUuid,
        TargetType targetType,
        List<Component> lines,
        String styleKey,
        boolean enabled,
        long updatedAt
) {
    public PersistedNameTag {
        lines = List.copyOf(lines);
        styleKey = styleKey == null || styleKey.isBlank() ? "default" : styleKey;
    }

    public enum TargetType {
        PLAYER,
        ENTITY
    }
}
