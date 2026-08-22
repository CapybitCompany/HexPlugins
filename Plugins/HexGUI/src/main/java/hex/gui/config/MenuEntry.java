package hex.gui.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record MenuEntry(
        String id,
        boolean enabled,
        int slot,
        String name,
        List<String> lore,
        String command,
        Action action,
        RunAs runAs,
        boolean closeOnClick,
        List<String> requiredPlugins,
        String permission,
        IconSpec icon
) {
    public enum Action { COMMAND, NONE }
    public enum RunAs { PLAYER, CONSOLE }

    public static MenuEntry from(String id, ConfigurationSection section, Plugin plugin) {
        boolean enabled = section.getBoolean("enabled", true);
        int slot = section.getInt("slot", -1);
        String name = section.getString("name", "&f" + id);
        List<String> lore = List.copyOf(section.getStringList("lore"));
        String command = normalizeCommand(section.getString("command", ""));
        Action action;
        try {
            action = Action.valueOf(section.getString("action", "COMMAND").trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            plugin.getLogger().warning("[config] entries." + id + ".action jest nieprawidłowe. Używam COMMAND.");
            action = Action.COMMAND;
        }

        boolean closeOnClick = section.getBoolean("close-on-click", true);
        String permission = clean(section.getString("permission", ""));

        RunAs runAs;
        try {
            runAs = RunAs.valueOf(section.getString("run-as", "PLAYER").trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            plugin.getLogger().warning("[config] entries." + id + ".run-as jest nieprawidłowe. Używam PLAYER.");
            runAs = RunAs.PLAYER;
        }

        Set<String> plugins = new LinkedHashSet<>(section.getStringList("required-plugins"));
        String singlePlugin = clean(section.getString("required-plugin", ""));
        if (!singlePlugin.isBlank()) plugins.add(singlePlugin);
        List<String> requiredPlugins = new ArrayList<>();
        for (String pluginName : plugins) {
            if (pluginName != null && !pluginName.isBlank()) requiredPlugins.add(pluginName.trim());
        }

        return new MenuEntry(
                id,
                enabled,
                slot,
                name == null ? "" : name,
                lore,
                command,
                action,
                runAs,
                closeOnClick,
                List.copyOf(requiredPlugins),
                permission,
                IconSpec.from(section.getConfigurationSection("icon"), plugin, id)
        );
    }

    public String commandRoot() {
        if (command == null || command.isBlank()) return "";
        int space = command.indexOf(' ');
        return (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static String normalizeCommand(String raw) {
        String result = clean(raw);
        while (result.startsWith("/")) result = result.substring(1).trim();
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
