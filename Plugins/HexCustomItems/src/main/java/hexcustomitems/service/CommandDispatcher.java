package hexcustomitems.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Kleine Abstraktion über {@code Bukkit.dispatchCommand}, damit Tests die von
 * COMMAND-Aktionen ausgelösten Befehle abfangen können.
 */
@FunctionalInterface
public interface CommandDispatcher {

    void dispatch(CommandSender sender, String command);

    /** Standard: leitet an Bukkit weiter. */
    CommandDispatcher BUKKIT = Bukkit::dispatchCommand;
}
