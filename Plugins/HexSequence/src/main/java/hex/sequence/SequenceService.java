package hex.sequence;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SequenceService {

    private final HexSequencePlugin plugin;

    public SequenceService(HexSequencePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean run(CommandSender sender, String sequenceName, List<SequenceEntry> entries) {
        Player playerContext = sender instanceof Player player ? player : null;

        if (playerContext == null && entries.stream().anyMatch(entry -> entry.executorType() == SequenceExecutorType.PLAYER)) {
            plugin.sendMessage(sender, "no-player-context", Map.of());
            return false;
        }

        Map<Long, List<SequenceEntry>> groupedByDelay = new TreeMap<>();
        for (SequenceEntry entry : entries) {
            groupedByDelay.computeIfAbsent(entry.delayTicks(), ignored -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<Long, List<SequenceEntry>> group : groupedByDelay.entrySet()) {
            long delayTicks = group.getKey();
            List<SequenceEntry> groupEntries = List.copyOf(group.getValue());
            if (delayTicks <= 0L) {
                runGroup(sender, playerContext, sequenceName, groupEntries);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> runGroup(sender, playerContext, sequenceName, groupEntries), delayTicks);
            }
        }

        return true;
    }

    private void runGroup(CommandSender sender, Player playerContext, String sequenceName, List<SequenceEntry> entries) {
        for (SequenceEntry entry : entries) {
            runEntry(sender, playerContext, sequenceName, entry);
        }
    }

    private void runEntry(CommandSender sender, Player playerContext, String sequenceName, SequenceEntry entry) {
        String command = applyPlaceholders(entry.command(), sender, playerContext, sequenceName);

        if (entry.executorType() == SequenceExecutorType.CONSOLE) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return;
        }

        if (playerContext == null || !playerContext.isOnline()) {
            plugin.getLogger().warning("Pominieto [player] w sekwencji " + sequenceName
                    + " linia " + entry.lineIndex() + ": gracz nie jest juz online.");
            return;
        }

        Bukkit.dispatchCommand(playerContext, command);
    }

    private String applyPlaceholders(String command, CommandSender sender, Player playerContext, String sequenceName) {
        String playerName = playerContext != null ? playerContext.getName() : sender.getName();
        return command
                .replace("%player_name%", playerName)
                .replace("%sequence_name%", sequenceName);
    }
}

