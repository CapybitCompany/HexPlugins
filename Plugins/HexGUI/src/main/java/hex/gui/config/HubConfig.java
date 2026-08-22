package hex.gui.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record HubConfig(
        String title,
        int size,
        Material fillerMaterial,
        boolean fillerHideTooltip,
        List<String> unavailableLore,
        Map<Integer, MenuEntry> entriesBySlot,
        String playerOnlyMessage,
        String noPermissionMessage,
        String unavailableMessage,
        String commandFailedMessage,
        String reloadedMessage,
        String reloadFailedMessage,
        Sound openSound,
        Sound unavailableSound,
        Sound errorSound
) {
    public static HubConfig load(Plugin plugin) {
        FileConfiguration config = plugin.getConfig();
        int rows = Math.max(1, Math.min(6, config.getInt("menu.rows", 5)));
        int size = rows * 9;
        String title = config.getString("menu.title", "&8Hex");

        Material filler = parseMaterial(
                config.getString("menu.filler.material", "BLACK_STAINED_GLASS_PANE"),
                Material.BLACK_STAINED_GLASS_PANE,
                plugin,
                "menu.filler.material"
        );
        boolean hideTooltip = config.getBoolean("menu.filler.hide-tooltip", true);
        List<String> unavailableLore = List.copyOf(config.getStringList("menu.unavailable-lore"));

        Map<Integer, MenuEntry> entries = new LinkedHashMap<>();
        ConfigurationSection entriesSection = config.getConfigurationSection("entries");
        if (entriesSection != null) {
            for (String id : entriesSection.getKeys(false)) {
                ConfigurationSection section = entriesSection.getConfigurationSection(id);
                if (section == null) continue;
                MenuEntry entry = MenuEntry.from(id, section, plugin);
                if (!entry.enabled()) continue;
                if (entry.slot() < 0 || entry.slot() >= size) {
                    plugin.getLogger().warning("[config] Pomijam entries." + id + ": slot " + entry.slot() + " jest poza zakresem 0-" + (size - 1) + ".");
                    continue;
                }
                if (entry.action() == MenuEntry.Action.COMMAND && entry.command().isBlank()) {
                    plugin.getLogger().warning("[config] entries." + id + " ma action=COMMAND, ale nie ma komendy. Pozycja będzie widoczna jako niedostępna.");
                }
                MenuEntry previous = entries.put(entry.slot(), entry);
                if (previous != null) {
                    plugin.getLogger().warning("[config] Slot " + entry.slot() + " był używany przez '" + previous.id() + "' i został zastąpiony przez '" + id + "'.");
                }
            }
        }

        return new HubConfig(
                title == null ? "&8Hex" : title,
                size,
                filler,
                hideTooltip,
                unavailableLore,
                Map.copyOf(entries),
                config.getString("messages.player-only", "&cTa komenda jest tylko dla graczy."),
                config.getString("messages.no-permission", "&cNie masz uprawnień."),
                config.getString("messages.unavailable", "&cTo menu jest chwilowo niedostępne."),
                config.getString("messages.command-failed", "&cNie udało się otworzyć menu."),
                config.getString("messages.reloaded", "&aHexGUI przeładowane."),
                config.getString("messages.reload-failed", "&cNie udało się przeładować HexGUI."),
                parseSound(config.getString("sounds.open", "UI_BUTTON_CLICK"), Sound.UI_BUTTON_CLICK, plugin, "sounds.open"),
                parseSound(config.getString("sounds.unavailable", "BLOCK_NOTE_BLOCK_BASS"), Sound.BLOCK_NOTE_BLOCK_BASS, plugin, "sounds.unavailable"),
                parseSound(config.getString("sounds.error", "ENTITY_VILLAGER_NO"), Sound.ENTITY_VILLAGER_NO, plugin, "sounds.error")
        );
    }

    public List<MenuEntry> entries() {
        return new ArrayList<>(entriesBySlot.values());
    }

    private static Material parseMaterial(String raw, Material fallback, Plugin plugin, String path) {
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("[config] " + path + "='" + raw + "' jest nieprawidłowe. Używam " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static Sound parseSound(String raw, Sound fallback, Plugin plugin, String path) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("[config] " + path + "='" + raw + "' jest nieprawidłowe. Używam " + fallback + ".");
            return fallback;
        }
    }
}
