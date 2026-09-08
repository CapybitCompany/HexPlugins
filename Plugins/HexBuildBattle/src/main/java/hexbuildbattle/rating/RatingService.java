package hexbuildbattle.rating;

import hexbuildbattle.config.ConfigParsers;
import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.SoundSetting;
import hexbuildbattle.item.ItemBuilder;
import hexbuildbattle.item.PluginItemKeys;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class RatingService {

    public static final String RATING_TYPE = "rating";
    public static final String REPORT_TYPE = "build_report";

    private final ConfigService configService;
    private final PluginItemKeys itemKeys;
    private final Logger logger;
    private Map<Integer, RatingDefinition> ratings = Map.of();

    public RatingService(ConfigService configService, PluginItemKeys itemKeys, Logger logger) {
        this.configService = configService;
        this.itemKeys = itemKeys;
        this.logger = logger;
    }

    public void reload() {
        Map<Integer, RatingDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection section = configService.ratings().getConfigurationSection("ratings");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    ConfigurationSection ratingSection = section.getConfigurationSection(key);
                    if (ratingSection == null) {
                        continue;
                    }
                    Material material = ConfigParsers.material(
                            ratingSection.getString("material"),
                            Material.PAPER,
                            logger,
                            "ratings." + key + ".material"
                    );
                    List<SoundSetting> sounds = loadSounds(ratingSection, key);
                    Particle particle = ConfigParsers.particle(
                            ratingSection.getString("particle"),
                            Particle.END_ROD,
                            logger,
                            "ratings." + key + ".particle"
                    );
                    loaded.put(level, new RatingDefinition(
                            level,
                            material,
                            ratingSection.getString("display-name", "&fRating " + level),
                            Math.max(0, ratingSection.getInt("points", 0)),
                            sounds,
                            particle
                    ));
                } catch (NumberFormatException ex) {
                    logger.warning("Invalid rating id '" + key + "' in ratings.yml.");
                }
            }
        }
        this.ratings = Map.copyOf(loaded);
        logger.info("Loaded " + ratings.size() + " Build Battle ratings.");
    }

    public List<RatingDefinition> sortedRatings() {
        return ratings.values().stream()
                .sorted(Comparator.comparingInt(RatingDefinition::level))
                .toList();
    }

    public Optional<RatingDefinition> ratingFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        Integer level = item.getItemMeta().getPersistentDataContainer()
                .get(itemKeys.ratingLevel(), PersistentDataType.INTEGER);
        if (level == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ratings.get(level));
    }

    public boolean isRatingItem(ItemStack item) {
        return ratingFromItem(item).isPresent();
    }

    public boolean isReportItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String type = item.getItemMeta().getPersistentDataContainer()
                .get(itemKeys.itemType(), PersistentDataType.STRING);
        return REPORT_TYPE.equals(type);
    }

    public Optional<RatingDefinition> highestRating() {
        return ratings.values().stream().max(Comparator.comparingInt(RatingDefinition::points));
    }

    public void giveRatingItems(Player player) {
        player.getInventory().clear();
        List<RatingDefinition> sorted = new ArrayList<>(sortedRatings());
        for (int i = 0; i < sorted.size() && i < 8; i++) {
            player.getInventory().setItem(i, item(sorted.get(i)));
        }
        player.getInventory().setItem(8, reportItem());
    }

    public void clearRatingItems(Player player) {
        player.getInventory().clear();
    }

    public void playRatingSound(Player player, RatingDefinition rating) {
        for (SoundSetting sound : rating.sounds()) {
            sound.play(player);
        }
        player.spawnParticle(rating.particle(), player.getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.35D, 0.35D, 0.35D, 0.02D);
    }

    public void playReportSound(Player player) {
        Sound sound = Material.BARRIER.createBlockData().getSoundGroup().getPlaceSound();
        player.playSound(player.getLocation(), sound, 1.0F, 0.8F);
    }

    private List<SoundSetting> loadSounds(ConfigurationSection ratingSection, String key) {
        List<SoundSetting> configured = ConfigParsers.soundSettingList(
                ratingSection,
                "sounds",
                logger,
                1.0D,
                1.0D
        );
        if (!configured.isEmpty()) {
            return configured;
        }

        List<SoundSetting> sounds = new ArrayList<>();
        String soundName = ratingSection.getString("sound");
        if (soundName != null && !soundName.isBlank()) {
            sounds.add(ConfigParsers.soundSetting(
                    ratingSection,
                    soundName,
                    ratingSection.getDouble("volume", 1.0D),
                    ratingSection.getDouble("pitch", 1.0D),
                    logger,
                    "ratings." + key
            ));
        }
        String extraSoundName = ratingSection.getString("extra-sound");
        if (extraSoundName != null && !extraSoundName.isBlank()) {
            sounds.add(new SoundSetting(
                    ConfigParsers.sound(extraSoundName, logger, "ratings." + key + ".extra-sound"),
                    (float) ratingSection.getDouble("extra-volume", 0.9D),
                    (float) ratingSection.getDouble("extra-pitch", 1.1D)
            ));
        }
        return List.copyOf(sounds);
    }

    private ItemStack item(RatingDefinition rating) {
        ItemStack item = ItemBuilder.named(rating.material(), rating.displayName());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKeys.itemType(), PersistentDataType.STRING, RATING_TYPE);
        meta.getPersistentDataContainer().set(itemKeys.ratingLevel(), PersistentDataType.INTEGER, rating.level());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack reportItem() {
        ItemStack item = ItemBuilder.named(
                Material.BARRIER,
                "&cZglos budowle",
                List.of("&7Kliknij, aby zapisac raport", "&7i schematic tej budowli.")
        );
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKeys.itemType(), PersistentDataType.STRING, REPORT_TYPE);
        item.setItemMeta(meta);
        return item;
    }
}
