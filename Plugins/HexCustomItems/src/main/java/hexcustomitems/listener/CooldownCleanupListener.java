package hexcustomitems.listener;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.service.CooldownService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Räumt Cooldown-Einträge verlassender Spieler auf, um die In-Memory-Map schlank zu halten.
 * Bei aktiver Persistenz bleiben die Einträge erhalten, damit sie gespeichert werden können.
 */
public final class CooldownCleanupListener implements Listener {

    private final CooldownService cooldownService;
    private final Supplier<HexCustomItemsConfig> configSupplier;

    public CooldownCleanupListener(CooldownService cooldownService, Supplier<HexCustomItemsConfig> configSupplier) {
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (configSupplier.get().cooldowns().persist()) {
            return;
        }
        cooldownService.clear(event.getPlayer().getUniqueId());
    }
}
