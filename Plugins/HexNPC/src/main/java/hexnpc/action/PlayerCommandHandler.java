package hexnpc.action;

import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.util.LegacyFormat;
import org.bukkit.entity.Player;

public final class PlayerCommandHandler implements NpcActionHandler {

    @Override
    public String id() {
        return "player-command";
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        String command = action.asString("command", "");
        if (command.isEmpty()) {
            return;
        }
        String rendered = LegacyFormat.replace(command, "<player>", player.getName());
        if (rendered.startsWith("/")) {
            rendered = rendered.substring(1);
        }
        player.performCommand(rendered);
    }
}
