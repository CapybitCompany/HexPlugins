package hex.gui.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class Text {
    private Text() {}

    public static String color(Player player, Plugin plugin, String raw) {
        String parsed = placeholders(player, plugin, raw == null ? "" : raw);
        return ChatColor.translateAlternateColorCodes('&', parsed);
    }

    public static List<String> color(Player player, Plugin plugin, List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines == null) return result;
        for (String line : lines) result.add(color(player, plugin, line));
        return result;
    }

    public static void send(CommandSender sender, Plugin plugin, String raw) {
        if (sender instanceof Player player) sender.sendMessage(color(player, plugin, raw));
        else sender.sendMessage(ChatColor.translateAlternateColorCodes('&', raw == null ? "" : raw));
    }

    private static String placeholders(Player player, Plugin plugin, String raw) {
        String result = raw
                .replace("{player}", player.getName())
                .replace("%player%", player.getName());

        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return result;
        try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = placeholderApi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            Object value = method.invoke(null, player, result);
            return value instanceof String string ? string : result;
        } catch (Throwable ignored) {
            return result;
        }
    }
}
