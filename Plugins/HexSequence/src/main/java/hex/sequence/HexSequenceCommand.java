package hex.sequence;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class HexSequenceCommand implements CommandExecutor, TabCompleter {

    private final HexSequencePlugin plugin;

    public HexSequenceCommand(HexSequencePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            plugin.sendMessage(sender, "usage", Map.of());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexsequence.admin")) {
                plugin.sendMessage(sender, "no-permission", Map.of());
                return true;
            }
            plugin.reloadPluginConfig();
            plugin.sendMessage(sender, "reloaded", Map.of());
            return true;
        }

        if (!sender.hasPermission("hexsequence.use")) {
            plugin.sendMessage(sender, "no-permission", Map.of());
            return true;
        }

        String sequenceName = args[0];
        List<SequenceEntry> entries;
        try {
            entries = plugin.configLoader().load(sequenceName);
        } catch (SequenceParseException ex) {
            plugin.sendMessage(sender, "parse-error", Map.of(
                    "sequence", sequenceName,
                    "error", ex.getMessage()
            ));
            return true;
        }

        if (entries == null) {
            plugin.sendMessage(sender, "unknown-sequence", Map.of("sequence", sequenceName));
            return true;
        }

        boolean started = plugin.sequenceService().run(sender, sequenceName, entries);
        if (started) {
            plugin.sendMessage(sender, "started", Map.of(
                    "sequence", sequenceName,
                    "count", String.valueOf(entries.size())
            ));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase();
        List<String> suggestions = new ArrayList<>();
        if (sender.hasPermission("hexsequence.admin")) {
            suggestions.add("reload");
        }
        if (sender.hasPermission("hexsequence.use")) {
            suggestions.addAll(plugin.configLoader().sequenceNames());
        }

        return suggestions.stream()
                .filter(value -> value.toLowerCase().startsWith(prefix))
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}

