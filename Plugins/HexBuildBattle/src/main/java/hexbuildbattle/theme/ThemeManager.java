package hexbuildbattle.theme;

import hexbuildbattle.config.ConfigParsers;
import hexbuildbattle.config.ConfigService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

public final class ThemeManager {

    private final ConfigService configService;
    private final Logger logger;
    private final Random random = new Random();
    private List<Theme> activeThemes = List.of();

    public ThemeManager(ConfigService configService, Logger logger) {
        this.configService = configService;
        this.logger = logger;
    }

    public void reload() {
        List<Theme> loaded = new ArrayList<>();
        ConfigurationSection section = configService.themes().getConfigurationSection("themes");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection themeSection = section.getConfigurationSection(id);
                if (themeSection == null || !themeSection.getBoolean("enabled", true)) {
                    continue;
                }
                String displayName = themeSection.getString("display-name", id);
                Material icon = ConfigParsers.material(
                        themeSection.getString("icon"),
                        Material.PAPER,
                        logger,
                        "themes." + id + ".icon"
                );
                int weight = Math.max(1, themeSection.getInt("weight", 1));
                loaded.add(new Theme(id, displayName, icon, weight));
            }
        }
        this.activeThemes = List.copyOf(loaded);
        logger.info("Loaded " + activeThemes.size() + " active Build Battle themes.");
    }

    public List<Theme> randomOptions(int count) {
        if (activeThemes.isEmpty()) {
            return List.of(new Theme("missing_themes", "Brak tematów", Material.BARRIER, 1));
        }

        List<Theme> pool = new ArrayList<>(activeThemes);
        List<Theme> result = new ArrayList<>(Math.min(count, pool.size()));
        while (!pool.isEmpty() && result.size() < count) {
            int totalWeight = pool.stream().mapToInt(Theme::weight).sum();
            int roll = random.nextInt(totalWeight);
            int cursor = 0;
            for (int i = 0; i < pool.size(); i++) {
                cursor += pool.get(i).weight();
                if (roll < cursor) {
                    result.add(pool.remove(i));
                    break;
                }
            }
        }
        return List.copyOf(result);
    }
}
