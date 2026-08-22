package hexnpc.guide;

import hexnpc.action.NpcActionHandler;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

import java.util.Objects;

public final class GuideMenuHandler implements NpcActionHandler {
    private final GuideMenuService service;

    public GuideMenuHandler(GuideMenuService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String id() { return "guide-menu"; }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        String menu = action.asString("menu", "").trim();
        if (menu.isEmpty()) throw new IllegalArgumentException("guide-menu requires 'menu'");
        service.open(player, menu);
    }
}
