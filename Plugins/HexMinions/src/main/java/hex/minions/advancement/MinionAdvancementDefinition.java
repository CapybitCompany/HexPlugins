package hex.minions.advancement;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

public record MinionAdvancementDefinition(
        String id,
        String path,
        String parent,
        String title,
        String description,
        Material icon,
        String frame,
        boolean showToast,
        boolean announceToChat,
        boolean hidden,
        int growthPoints,
        int minionLimitBonus,
        MinionAdvancementRequirement requirement
) {
    public static MinionAdvancementDefinition fromConfig(String id, ConfigurationSection section) {
        String path = section.getString("path", section.getString("key", id.replace('.', '/')));
        String parent = section.getString("parent", "");
        String title = section.getString("title", id);
        String description = section.getString("description", "");
        Material icon = parseMaterial(section.getString("icon", "STONE"), Material.STONE);
        String frame = section.getString("frame", "task");
        return new MinionAdvancementDefinition(
                id,
                normalizePath(path),
                normalizePath(parent),
                title,
                description,
                icon,
                frame == null ? "task" : frame.toLowerCase(Locale.ROOT),
                section.getBoolean("show-toast", true),
                section.getBoolean("announce-to-chat", false),
                section.getBoolean("hidden", false),
                Math.max(0, section.getInt("growth-points", section.getInt("growth-reward", 0))),
                Math.max(0, section.getInt("minion-limit-bonus", section.getInt("limit-bonus", section.getInt("minion-slots", 0)))),
                MinionAdvancementRequirement.fromConfig(section.getConfigurationSection("requirement"))
        );
    }

    public NamespacedKey key(Plugin plugin, String namespace) {
        return new NamespacedKey(namespace == null || namespace.isBlank() ? plugin.getName().toLowerCase(Locale.ROOT) : namespace.toLowerCase(Locale.ROOT), path);
    }

    public String parentKey(String namespace) {
        if (parent == null || parent.isBlank()) return "";
        String ns = namespace == null || namespace.isBlank() ? "hexminions" : namespace.toLowerCase(Locale.ROOT);
        return ns + ":" + parent;
    }

    private static Material parseMaterial(String raw, Material def) {
        if (raw == null || raw.isBlank()) return def;
        Material material = Material.matchMaterial(raw);
        return material == null ? def : material;
    }

    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (value.contains(":")) value = value.substring(value.indexOf(':') + 1);
        return value;
    }
}
