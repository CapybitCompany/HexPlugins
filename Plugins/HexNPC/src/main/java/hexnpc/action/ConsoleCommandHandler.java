package hexnpc.action;

import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ConsoleCommandHandler implements NpcActionHandler {

    @Override
    public String id() {
        return "console-command";
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        String command = action.asString("command", "");
        if (command.isEmpty()) {
            return;
        }
        String rendered = LegacyFormat.replace(command, "<player>", player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
    }
}
