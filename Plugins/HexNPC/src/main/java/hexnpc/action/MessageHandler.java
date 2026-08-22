package hexnpc.action;

import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.util.LegacyFormat;
import org.bukkit.entity.Player;

public final class MessageHandler implements NpcActionHandler {

    @Override
    public String id() {
        return "message";
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        String text = action.asString("text", "");
        if (text.isEmpty()) {
            return;
        }
        String rendered = LegacyFormat.replace(text, "<player>", player.getName());
        player.sendMessage(LegacyFormat.component(rendered));
    }
}
