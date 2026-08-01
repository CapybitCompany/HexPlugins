package hexcasino;

import hexcasino.config.CasinoConfig;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CasinoEconomy {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final DecimalFormat MONEY_FORMAT =
            new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private CasinoEconomy() {
    }

    public static OptionalDouble balance(Player player, CasinoConfig config) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return OptionalDouble.empty();
        }
        try {
            return parseMoney(PlaceholderAPI.setPlaceholders(player, config.economy().balancePlaceholder()));
        } catch (NoClassDefFoundError ex) {
            return OptionalDouble.empty();
        }
    }

    public static boolean dispatch(String rawCommand, Player player, double amount) {
        String command = Text.apply(rawCommand, Map.of(
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "amount", money(amount)
        )).trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        return !command.isBlank() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public static String money(double value) {
        return MONEY_FORMAT.format(value);
    }

    private static OptionalDouble parseMoney(String raw) {
        String plain = ChatColor.stripColor(raw == null ? "" : raw).trim();
        plain = plain.replace(" ", "").replace("$", "");
        if (plain.contains(",") && plain.contains(".")) {
            plain = plain.replace(",", "");
        } else {
            plain = plain.replace(",", ".");
        }
        Matcher matcher = NUMBER_PATTERN.matcher(plain);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(matcher.group()));
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }
}
