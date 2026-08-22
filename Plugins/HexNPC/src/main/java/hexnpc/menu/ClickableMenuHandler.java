package hexnpc.menu;

import hexnpc.action.NpcActionHandler;
import hexnpc.guide.GuideMenuService;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

public final class ClickableMenuHandler implements NpcActionHandler {

    private final ClickableMenuService menus;
    private final GuideMenuService guides;

    public ClickableMenuHandler(ClickableMenuService menus) {
        this(menus, null);
    }

    public ClickableMenuHandler(ClickableMenuService menus, GuideMenuService guides) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.guides = guides;
    }

    @Override
    public String id() {
        return "clickable-menu";
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        Object raw = action.args().get("menu");
        String menuId = raw == null ? "" : String.valueOf(raw).trim();
        if (menuId.isBlank()) {
            throw new IllegalArgumentException("clickable-menu requires 'menu'");
        }
        String normalized = menuId.toLowerCase(Locale.ROOT);
        if (guides != null && guides.exists("server") && normalized.equals("tutorial_on-click_0")) {
            if (guides.open(player, "server")) return;
        }
        if (guides != null && guides.exists("arcade") && normalized.equals("kasyno_info_on-click_0")) {
            if (guides.open(player, "arcade")) return;
        }
        menus.open(player, npc, menuId);
    }
}
