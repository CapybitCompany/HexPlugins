package hexpvpsmp.combat;

import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

/**
 * Centralizes the "OPs / bypass-perm holders ignore restrictions" rule.
 * OP bypass is implicit: any OP returns true even without the explicit perm.
 */
public final class PermissionGate {

    public static final String BYPASS_PERMISSION = "hexpvpsmp.bypass";

    private PermissionGate() {
    }

    public static boolean bypasses(Permissible permissible) {
        if (permissible == null) {
            return false;
        }
        if (permissible instanceof Player p && p.isOp()) {
            return true;
        }
        return permissible.hasPermission(BYPASS_PERMISSION);
    }
}
