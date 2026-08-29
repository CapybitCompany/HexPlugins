package hex.events.provider;

import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;
import hex.events.api.RequirementCheck;
import hex.events.api.RequirementProvider;
import hexcustomitems.api.HexCustomItemsApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class CustomItemPresentRequirementProvider implements RequirementProvider {
    @Override public String type() { return "custom_item_present"; }
    private HexCustomItemsApi api() {
        var registration = Bukkit.getServicesManager().getRegistration(HexCustomItemsApi.class);
        return registration == null ? null : registration.getProvider();
    }
    @Override public RequirementCheck check(PlayerContext player, EventModuleSettings settings) {
        HexCustomItemsApi api = api();
        Player online = Bukkit.getPlayer(player.playerId());
        if (api == null) return RequirementCheck.fail("HexCustomItems jest niedostępny.");
        if (online == null) return RequirementCheck.fail("Gracz musi być online.");
        String itemId = settings.string("item-id", "");
        int amount = Math.max(1, settings.integer("amount", 1));
        if (itemId.isBlank()) return RequirementCheck.fail("Brak item-id w konfiguracji.");
        return api.has(online, itemId, amount) ? RequirementCheck.ok() : RequirementCheck.fail("Brakuje wymaganego przedmiotu: " + itemId + " x" + amount);
    }
    @Override public boolean available() { return api() != null; }
    @Override public String unavailableReason() { return available() ? "" : "HexCustomItems API unavailable"; }
}
