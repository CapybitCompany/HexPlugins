package hexmobplaceholder.config;

import org.bukkit.entity.EntityType;

import java.util.Set;

public record MobPlaceholderConfig(Set<EntityType> hostileMobs, Placeholders placeholders, Messages messages) {

    public record Placeholders(String noTopPlayer) {
    }

    public record Messages(
            String prefix,
            String usage,
            String noPermission,
            String reloadSuccess,
            String reloadFailed,
            String playerNotFound,
            String resetSuccess,
            String resetFailed
    ) {

        public String withPrefix(String message) {
            return prefix + message;
        }
    }
}
