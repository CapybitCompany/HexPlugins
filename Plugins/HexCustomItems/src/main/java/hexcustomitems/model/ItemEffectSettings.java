package hexcustomitems.model;

import java.util.List;
import java.util.Objects;

public record ItemEffectSettings(
        CustomItemEffectType type,
        int coins,
        String commandTemplate,
        PotionEffectSpec potionEffect,
        double radius,
        boolean affectSelf,
        double maxDistance,
        int fireSeconds,
        List<PotionEffectSpec> areaEffects
) {
    public ItemEffectSettings {
        type = Objects.requireNonNull(type, "type");
        commandTemplate = commandTemplate == null ? "" : commandTemplate;
        radius = Math.max(0.5D, radius);
        maxDistance = Math.max(1.0D, maxDistance);
        fireSeconds = Math.max(1, fireSeconds);
        areaEffects = List.copyOf(Objects.requireNonNull(areaEffects, "areaEffects"));
    }
}
