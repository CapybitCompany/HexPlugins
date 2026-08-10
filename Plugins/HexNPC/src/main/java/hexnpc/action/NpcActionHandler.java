package hexnpc.action;

import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

/**
 * Extension point for NPC action types. Bundled handlers cover
 * console-command / player-command / message / clickable-message. AuctionHouse, Bazaar,
 * NPC-Shop, Quest plugins register their own handlers at enable time
 * via the {@code NpcActionRegistry} exposed on Bukkit's ServicesManager.
 */
public interface NpcActionHandler {

    String id();

    void execute(Player player, NpcDefinition npc, NpcAction action);
}
