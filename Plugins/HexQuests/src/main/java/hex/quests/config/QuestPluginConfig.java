package hex.quests.config;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.List;

public record QuestPluginConfig(
        ZoneId zoneId,
        int dailyCount,
        int retentionDays,
        long selectionSeed,
        String menuTitle,
        List<Integer> questSlots,
        int infoSlot,
        String infoName,
        List<String> infoLore,
        Sound completionSound,
        float completionVolume,
        float completionPitch,
        String prefix,
        String loadingMessage,
        String completedMessage,
        String reloadMessage,
        String noPermissionMessage
) {
    public static QuestPluginConfig load(FileConfiguration config) {
        ZoneId zone;
        try { zone = ZoneId.of(config.getString("daily.time-zone", "Europe/Warsaw")); }
        catch (Exception ignored) { zone = ZoneId.of("Europe/Warsaw"); }
        int count = Math.max(1, config.getInt("daily.count", 3));
        int retention = Math.min(7, Math.max(1, config.getInt("database.retention-days", 7)));
        long seed = config.getLong("daily.selection-seed", 0x4845585155455354L);
        List<Integer> slots = config.getIntegerList("menu.quest-slots");
        if (slots.size() < count) slots = List.of(11, 13, 15);
        Sound sound;
        try { sound = Sound.valueOf(config.getString("completion.sound", "UI_TOAST_CHALLENGE_COMPLETE").toUpperCase()); }
        catch (Exception ignored) { sound = Sound.UI_TOAST_CHALLENGE_COMPLETE; }
        return new QuestPluginConfig(
                zone,
                count,
                retention,
                seed,
                color(config.getString("menu.title", "&8Codzienne zadania")),
                List.copyOf(slots),
                config.getInt("menu.info-slot", 4),
                color(config.getString("menu.info-name", "&eCodzienne zadania")),
                config.getStringList("menu.info-lore").stream().map(QuestPluginConfig::color).toList(),
                sound,
                (float) config.getDouble("completion.volume", 1.0),
                (float) config.getDouble("completion.pitch", 1.0),
                color(config.getString("messages.prefix", "&8[&6Daily&8] &7")),
                color(config.getString("messages.loading", "Ładowanie zadań...")),
                color(config.getString("messages.completed", "Ukończyłeś zadanie &e<quest>&7!")),
                color(config.getString("messages.reloaded", "Konfiguracja HexQuests została przeładowana.")),
                color(config.getString("messages.no-permission", "Nie masz uprawnień."))
        );
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
