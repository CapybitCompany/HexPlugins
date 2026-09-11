package hexnpc.event;

import hexnpc.action.NpcActionHandler;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class EventGuyMenuActionHandler implements NpcActionHandler {

    public static final String ID = "event-guy-menu";

    private final EventGuyMenuService menuService;

    public EventGuyMenuActionHandler(EventGuyMenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        menuService.open(
                player,
                action.asString("shop", EventGuyMenuService.DEFAULT_SHOP_ID),
                action.asString("event-command", EventGuyMenuService.DEFAULT_EVENT_COMMAND),
                action.asString("title", EventGuyMenuService.DEFAULT_TITLE));
    }
}
