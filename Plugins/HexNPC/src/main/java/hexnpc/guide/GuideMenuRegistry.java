package hexnpc.guide;

import hexnpc.guide.model.GuideBackground;
import hexnpc.guide.model.GuideEntry;
import hexnpc.guide.model.GuideIcon;
import hexnpc.guide.model.GuideMenu;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/** Immutable-snapshot registry loaded from guide-menus.yml. Invalid menus do not disable HexNPC. */
public final class GuideMenuRegistry {

    private final File file;
    private final Logger logger;
    private volatile Map<String, GuideMenu> menus = Map.of();
    private volatile List<String> validationErrors = List.of();

    public GuideMenuRegistry(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized int reload() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("guides");
        Map<String, GuideMenu> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (root == null) {
            errors.add("missing top-level 'guides' section");
            menus = Map.of();
            validationErrors = List.copyOf(errors);
            logger.warning("HexNPC: guide-menus.yml has no 'guides' section.");
            return 0;
        }

        for (String rawId : root.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            try {
                GuideMenu menu = readMenu(id, section, errors);
                loaded.put(id, menu);
            } catch (RuntimeException ex) {
                String message = "guide menu '" + id + "' skipped: " + ex.getMessage();
                errors.add(message);
                logger.warning("HexNPC: " + message);
            }
        }

        validateReferences(loaded, errors);
        menus = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        validationErrors = List.copyOf(errors);
        return menus.size();
    }

    private GuideMenu readMenu(String id, ConfigurationSection section, List<String> errors) {
        String title = section.getString("title", "&0&l" + id.toUpperCase(Locale.ROOT));
        int size = section.getInt("size", 27);
        if (size <= 0 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("invalid size " + size + " (allowed: 9..54, multiple of 9)");
        }
        String parent = normalize(section.getString("parent"));
        int backSlot = section.getInt("back-slot", 20);
        if (parent != null && (backSlot < 0 || backSlot >= size)) {
            throw new IllegalArgumentException("back-slot outside inventory: " + backSlot);
        }

        GuideBackground background = readBackground(section.getConfigurationSection("background"), id, errors);
        Map<Integer, GuideEntry> entries = new LinkedHashMap<>();
        ConfigurationSection entriesSection = section.getConfigurationSection("entries");
        if (entriesSection != null) {
            for (String entryId : entriesSection.getKeys(false)) {
                ConfigurationSection entrySection = entriesSection.getConfigurationSection(entryId);
                if (entrySection == null) continue;
                try {
                    GuideEntry entry = readEntry(id, entryId, entrySection, size);
                    if (parent != null && entry.slot() == backSlot) {
                        errors.add("guide menu '" + id + "' entry '" + entryId + "' collides with back-slot " + backSlot);
                        continue;
                    }
                    if (entries.putIfAbsent(entry.slot(), entry) != null) {
                        errors.add("guide menu '" + id + "' has duplicate slot " + entry.slot() + " (entry '" + entryId + "' skipped)");
                    }
                } catch (RuntimeException ex) {
                    errors.add("guide menu '" + id + "' entry '" + entryId + "' skipped: " + ex.getMessage());
                }
            }
        }
        return new GuideMenu(id, title, size, parent, backSlot, background, entries);
    }

    private GuideBackground readBackground(ConfigurationSection section, String menuId, List<String> errors) {
        if (section == null) return GuideBackground.defaults();
        String raw = section.getString("material", "BLACK_STAINED_GLASS_PANE");
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        if (material == null || material.isAir()) {
            errors.add("guide menu '" + menuId + "' has invalid background material '" + raw + "'; using BLACK_STAINED_GLASS_PANE");
            material = Material.BLACK_STAINED_GLASS_PANE;
        }
        return new GuideBackground(material, section.getBoolean("hide-tooltip", true));
    }

    private GuideEntry readEntry(String menuId, String entryId, ConfigurationSection section, int size) {
        int slot = section.getInt("slot", -1);
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException("slot outside inventory: " + slot);
        }
        ConfigurationSection iconSection = section.getConfigurationSection("icon");
        if (iconSection == null) {
            throw new IllegalArgumentException("missing icon section");
        }
        String rawMaterial = iconSection.getString("material", "PAPER");
        Material material = Material.matchMaterial(rawMaterial == null ? "" : rawMaterial);
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("unknown icon material '" + rawMaterial + "'");
        }
        Integer cmd = iconSection.contains("custom-model-data") ? iconSection.getInt("custom-model-data") : null;
        if (cmd != null && cmd < 0) {
            throw new IllegalArgumentException("custom-model-data must be >= 0");
        }
        String name = iconSection.getString("name", "&f" + entryId);
        List<String> lore = iconSection.getStringList("lore");
        String target = normalize(section.getString("target"));
        return new GuideEntry(entryId, slot, new GuideIcon(material, cmd, name, lore), target);
    }

    private void validateReferences(Map<String, GuideMenu> loaded, List<String> errors) {
        for (GuideMenu menu : loaded.values()) {
            if (menu.parent() != null && !loaded.containsKey(menu.parent())) {
                errors.add("guide menu '" + menu.id() + "' points to unknown parent '" + menu.parent() + "'");
            }
            for (GuideEntry entry : menu.entries().values()) {
                if (entry.target() != null && !loaded.containsKey(entry.target())) {
                    errors.add("guide menu '" + menu.id() + "' entry '" + entry.id()
                            + "' points to unknown target '" + entry.target() + "'");
                }
            }
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<GuideMenu> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(menus.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean exists(String id) {
        return find(id).isPresent();
    }

    public List<GuideMenu> all() {
        return List.copyOf(menus.values());
    }

    public List<String> ids() {
        return List.copyOf(menus.keySet());
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public int size() {
        return menus.size();
    }
}
