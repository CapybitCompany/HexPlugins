package hex.panel2.util;

import org.bukkit.entity.Player;

public final class AccessControl {

    private AccessControl() {
    }

    public static boolean isBypass(Player player) {
        return player.isOp() || player.hasPermission("hex.panel2.bypass");
    }
}
