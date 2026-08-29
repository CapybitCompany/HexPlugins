package hex.events.provider;

import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;
import hex.events.api.RequirementCheck;
import hex.events.api.RequirementProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PermissionRequirementProvider implements RequirementProvider {
    @Override public String type() { return "permission"; }
    @Override public RequirementCheck check(PlayerContext player, EventModuleSettings settings) {
        Player online = Bukkit.getPlayer(player.playerId());
        String permission = settings.string("permission", settings.string("value", ""));
        if (permission.isBlank()) return RequirementCheck.fail("Brak permission w konfiguracji eventu.");
        if (online == null) return RequirementCheck.fail("Gracz musi być online.");
        return online.hasPermission(permission) ? RequirementCheck.ok() : RequirementCheck.fail("Nie masz wymaganego uprawnienia: " + permission);
    }
}
