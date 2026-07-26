package hexmobplaceholder.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class MobPlaceholderConfigLoader {

    public MobPlaceholderConfig load(FileConfiguration config, Logger logger) {
        Set<EntityType> hostileMobs = EnumSet.noneOf(EntityType.class);
        for (String rawType : config.getStringList("hostile-mobs")) {
            String normalized = rawType.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                EntityType type = EntityType.valueOf(normalized);
                if (type.isAlive()) {
                    hostileMobs.add(type);
                } else {
                    logger.warning("Ignored non-living entity type in hostile-mobs: " + rawType);
                }
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignored unknown entity type in hostile-mobs: " + rawType);
            }
        }

        if (hostileMobs.isEmpty()) {
            throw new IllegalArgumentException("hostile-mobs cannot be empty");
        }

        MobPlaceholderConfig.Placeholders placeholders = new MobPlaceholderConfig.Placeholders(
                config.getString("placeholders.no-top-player", "-")
        );

        MobPlaceholderConfig.Messages messages = new MobPlaceholderConfig.Messages(
                config.getString("messages.prefix", "&8[&cHexMobPlaceholder&8] "),
                config.getString("messages.usage", "&7Uzycie: &f/hexmobplaceholder <debug|reload|reset <gracz>>"),
                config.getString("messages.no-permission", "&cNie masz uprawnien."),
                config.getString("messages.reload-success", "&aPrzeladowano konfiguracje i dane."),
                config.getString("messages.reload-failed", "&cNie udalo sie przeladowac konfiguracji."),
                config.getString("messages.player-not-found", "&cNie znaleziono gracza &f{player}&c."),
                config.getString("messages.reset-success", "&aWyzerowano postep agresywnych mobow dla gracza &f{player}&a."),
                config.getString("messages.reset-failed", "&cNie udalo sie zapisac wyzerowanego postepu dla gracza &f{player}&c.")
        );
        return new MobPlaceholderConfig(Set.copyOf(hostileMobs), placeholders, messages);
    }
}
