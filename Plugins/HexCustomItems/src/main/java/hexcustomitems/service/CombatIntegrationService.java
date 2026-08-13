package hexcustomitems.service;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class CombatIntegrationService {

    private final JavaPlugin plugin;

    public CombatIntegrationService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isInCombat(Player player) {
        Plugin pvp = plugin.getServer().getPluginManager().getPlugin("HexPvpSmp");
        if (pvp == null || !pvp.isEnabled()) {
            return false;
        }
        try {
            Method combatTagServiceMethod = pvp.getClass().getMethod("combatTagService");
            Object combatTagService = combatTagServiceMethod.invoke(pvp);
            if (combatTagService == null) {
                return false;
            }
            Method isTaggedMethod = combatTagService.getClass().getMethod("isTagged", Player.class);
            Object result = isTaggedMethod.invoke(combatTagService, player);
            return result instanceof Boolean tagged && tagged;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Nie udało się odczytać combat-taga z HexPvpSmp: " + ex.getMessage());
            return false;
        }
    }
}
