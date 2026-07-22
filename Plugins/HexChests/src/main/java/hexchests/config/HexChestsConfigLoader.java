package hexchests.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class HexChestsConfigLoader {

    public HexChestsConfig load(FileConfiguration config, Logger logger) {
        HexChestsConfig.Messages messages = new HexChestsConfig.Messages(
                config.getString("messages.prefix", "&6&lHexChests &8> "),
                config.getString("messages.no-permission", "&cNie masz uprawnien."),
                config.getString("messages.player-only", "&cTa komenda jest dostepna tylko dla gracza."),
                config.getString("messages.usage", "&7Uzycie: &f/hexchests <reload|afkkey|epickey|premiumkey>"),
                config.getString("messages.reload-success", "&aPrzeladowano konfiguracje."),
                config.getString("messages.reload-failed", "&cNie udalo sie przeladowac konfiguracji. Sprawdz konsole."),
                config.getString("messages.key-given", "&aOtrzymales klucz: &f{key_name}"),
                config.getString("messages.wrong-key-actionbar", "&cTen klucz nie pasuje do tej skrzyni."),
                config.getString("messages.opening-started-actionbar", "&6Trwa losowanie..."),
                config.getString("messages.reward-actionbar", "&6Otrzymano: &f{reward_name} x{amount}"),
                config.getString("messages.inventory-full", "&eNie wszystko zmiescilo sie w ekwipunku.")
        );
        HexChestsConfig.Sounds sounds = new HexChestsConfig.Sounds(
                sound(config.getConfigurationSection("sounds.preview"), "minecraft:ui.button.click", 0.5F, 1.2F),
                sound(config.getConfigurationSection("sounds.wrong-key"), "minecraft:block.note_block.bass", 0.6F, 0.7F),
                sound(config.getConfigurationSection("sounds.opening-tick"), "minecraft:block.note_block.hat", 0.35F, 1.4F),
                sound(config.getConfigurationSection("sounds.reward"), "minecraft:ui.toast.challenge_complete", 0.8F, 1.0F)
        );
        HexChestsConfig.Gui gui = gui(config, logger);
        HexChestsConfig.TestKeys testKeys = testKeys(config, logger);
        Map<String, HexChestsConfig.ChestDefinition> chests = chests(config, logger);
        return new HexChestsConfig(config.getBoolean("enabled", true), messages, sounds, gui, testKeys, chests);
    }

    private HexChestsConfig.Gui gui(FileConfiguration config, Logger logger) {
        int size = config.getInt("gui.size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warning("HexChests: gui.size must be a multiple of 9 between 9 and 54. Using 27.");
            size = 27;
        }
        HexChestsConfig.GuiItem filler = item(config.getConfigurationSection("gui.filler"),
                Material.BLACK_STAINED_GLASS_PANE, "", List.of(), true, logger);
        List<Integer> rewardSlots = slots(config.getIntegerList("gui.reward-slots"), size, defaultRewardSlots());
        HexChestsConfig.GuiItem infoItem = item(config.getConfigurationSection("gui.info-item"),
                Material.BOOK, "&6Informacje", List.of("&7Podglad nagrod."), false, logger);
        HexChestsConfig.OpeningGui opening = new HexChestsConfig.OpeningGui(
                Math.max(5, config.getInt("gui.opening.duration-ticks", 60)),
                Math.max(1, config.getInt("gui.opening.tick-interval-ticks", 2)),
                config.getInt("gui.opening.rolling-slot", 13),
                config.getInt("gui.opening.result-slot", 13),
                slots(config.getIntegerList("gui.opening.side-slots"), size, List.of(10, 11, 12, 14, 15, 16)),
                slots(config.getIntegerList("gui.opening.indicator-slots"), size, List.of(4, 22)),
                item(config.getConfigurationSection("gui.opening.indicator-item"),
                        Material.LIME_STAINED_GLASS_PANE, "", List.of(), true, logger),
                item(config.getConfigurationSection("gui.opening.rolling-filler"),
                        Material.YELLOW_STAINED_GLASS_PANE, "", List.of(), true, logger)
        );
        return new HexChestsConfig.Gui(size,
                config.getString("gui.preview-title", "{chest_name} &8- Podglad"),
                config.getString("gui.opening-title", "{chest_name} &8- Losowanie"),
                filler,
                rewardSlots,
                config.getInt("gui.info-slot", 49),
                infoItem,
                opening
        );
    }

    private HexChestsConfig.GuiItem item(ConfigurationSection section,
                                        Material fallbackMaterial,
                                        String fallbackName,
                                        List<String> fallbackLore,
                                        boolean fallbackHideTooltip,
                                        Logger logger) {
        if (section == null) {
            return new HexChestsConfig.GuiItem(fallbackMaterial, fallbackName, fallbackLore, fallbackHideTooltip);
        }
        return new HexChestsConfig.GuiItem(
                material(section.getString("material", fallbackMaterial.name()), fallbackMaterial, logger),
                section.getString("name", fallbackName),
                section.contains("lore") ? List.copyOf(section.getStringList("lore")) : fallbackLore,
                section.getBoolean("hide-tooltip", section.getBoolean("hide_tooltip", fallbackHideTooltip))
        );
    }

    private HexChestsConfig.TestKeys testKeys(FileConfiguration config, Logger logger) {
        Map<String, HexChestsConfig.KeyDefinition> keys = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("test-keys.keys");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection item = section.getConfigurationSection(key);
                if (item == null) {
                    continue;
                }
                String id = normalizeId(key);
                int customModelData = item.getInt("custom-model-data", 0);
                keys.put(id, new HexChestsConfig.KeyDefinition(
                        id,
                        item.getString("command", id + "key").toLowerCase(Locale.ROOT),
                        material(item.getString("material", "TRIPWIRE_HOOK"), Material.TRIPWIRE_HOOK, logger),
                        customModelData > 0 ? customModelData : null,
                        item.getString("display-name", "&6" + key),
                        List.copyOf(item.getStringList("lore"))
                ));
            }
        }
        return new HexChestsConfig.TestKeys(config.getBoolean("test-keys.enabled", true), Collections.unmodifiableMap(keys));
    }

    private Map<String, HexChestsConfig.ChestDefinition> chests(FileConfiguration config, Logger logger) {
        Map<String, HexChestsConfig.ChestDefinition> chests = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("chests");
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection chest = section.getConfigurationSection(key);
            if (chest == null) {
                continue;
            }
            String id = normalizeId(key);
            chests.put(id, new HexChestsConfig.ChestDefinition(
                    id,
                    chest.getString("display-name", key),
                    location(chest.getConfigurationSection("location")),
                    material(chest.getString("block-material", "CHEST"), Material.CHEST, logger),
                    normalizeId(chest.getString("required-key", id)),
                    material(chest.getString("preview-material", chest.getString("block-material", "CHEST")),
                            Material.CHEST, logger),
                    rewards(chest.getConfigurationSection("rewards"), "chests." + key, logger)
            ));
        }
        return Collections.unmodifiableMap(chests);
    }

    private HexChestsConfig.BlockLocation location(ConfigurationSection section) {
        if (section == null) {
            return new HexChestsConfig.BlockLocation("world", 0, 0, 0);
        }
        return new HexChestsConfig.BlockLocation(
                section.getString("world", "world"),
                section.getInt("x"),
                section.getInt("y"),
                section.getInt("z")
        );
    }

    private List<HexChestsConfig.RewardDefinition> rewards(ConfigurationSection section, String label, Logger logger) {
        if (section == null) {
            return List.of();
        }
        List<HexChestsConfig.RewardDefinition> rewards = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null) {
                continue;
            }
            double chance = Math.max(0.0D, reward.getDouble("chance", 1.0D));
            if (chance <= 0.0D) {
                logger.warning("HexChests: reward " + label + "." + key + " has chance <= 0 and will be skipped.");
                continue;
            }
            rewards.add(new HexChestsConfig.RewardDefinition(
                    normalizeId(key),
                    material(reward.getString("material", "CHEST"), Material.CHEST, logger),
                    reward.getString("display-name", key),
                    Math.max(1, reward.getInt("amount", 1)),
                    chance,
                    reward.contains("lore") ? List.copyOf(reward.getStringList("lore")) : List.of(),
                    reward.contains("commands") ? List.copyOf(reward.getStringList("commands")) : List.of()
            ));
        }
        return List.copyOf(rewards);
    }

    private HexChestsConfig.SoundSetting sound(ConfigurationSection section, String fallback, float volume, float pitch) {
        if (section == null) {
            return new HexChestsConfig.SoundSetting(true, fallback, volume, pitch);
        }
        return new HexChestsConfig.SoundSetting(
                section.getBoolean("enabled", true),
                section.getString("name", fallback),
                (float) section.getDouble("volume", volume),
                (float) section.getDouble("pitch", pitch)
        );
    }

    private Material material(String raw, Material fallback, Logger logger) {
        Material found = Material.matchMaterial(raw == null ? "" : raw);
        if (found == null || !found.isItem()) {
            logger.warning("HexChests: unknown material '" + raw + "'. Using " + fallback + ".");
            return fallback;
        }
        return found;
    }

    private List<Integer> slots(List<Integer> configured, int size, List<Integer> fallback) {
        if (configured == null || configured.isEmpty()) {
            return fallback;
        }
        List<Integer> out = configured.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < size)
                .distinct()
                .toList();
        return out.isEmpty() ? fallback : out;
    }

    private List<Integer> defaultRewardSlots() {
        return List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
    }

    private String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        return raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
    }
}
