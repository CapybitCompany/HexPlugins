package hexnpc.api;

import org.bukkit.entity.Player;

/** Public Bukkit service for opening HexNPC guide menus from other plugins. */
public interface HexNpcGuideApi {
    boolean openGuide(Player player);
    boolean openMenu(Player player, String menuId);
    boolean exists(String menuId);
}
