package hex.events.provider;

import hex.events.api.CostCheck;
import hex.events.api.CostOperationResult;
import hex.events.api.CostProvider;
import hex.events.api.CostReceipt;
import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;
import hexcustomitems.api.HexCustomItemsApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public final class CustomItemCostProvider implements CostProvider {
    @Override public String type() { return "custom_item"; }
    private HexCustomItemsApi api() {
        var registration = Bukkit.getServicesManager().getRegistration(HexCustomItemsApi.class);
        return registration == null ? null : registration.getProvider();
    }
    @Override public CostCheck validate(PlayerContext player, EventModuleSettings settings) {
        HexCustomItemsApi api = api(); Player online = Bukkit.getPlayer(player.playerId());
        String itemId = settings.string("item-id", ""); int amount = Math.max(1, settings.integer("amount", 1));
        if (api == null) return CostCheck.fail("HexCustomItems jest niedostępny.");
        if (online == null) return CostCheck.fail("Gracz musi być online.");
        if (itemId.isBlank()) return CostCheck.fail("Brak item-id.");
        return api.has(online, itemId, amount) ? CostCheck.ok() : CostCheck.fail("Brakuje " + itemId + " x" + amount);
    }
    @Override public CostOperationResult charge(PlayerContext player, EventModuleSettings settings, String costId, String idempotencyKey) {
        HexCustomItemsApi api = api(); Player online = Bukkit.getPlayer(player.playerId());
        if (api == null || online == null) return CostOperationResult.failed("CUSTOM_ITEMS_OR_PLAYER_UNAVAILABLE", false);
        String itemId = settings.string("item-id", ""); int amount = Math.max(1, settings.integer("amount", 1));
        var result = api.take(online, itemId, amount);
        if (!result.success()) return CostOperationResult.failed(result.reason(), false);
        return CostOperationResult.charged(new CostReceipt(type(), costId, Map.of("item-id", itemId, "amount", String.valueOf(amount))));
    }
    @Override public CostOperationResult refund(PlayerContext player, CostReceipt receipt, String idempotencyKey) {
        HexCustomItemsApi api = api(); Player online = Bukkit.getPlayer(player.playerId());
        if (api == null) return CostOperationResult.failed("HexCustomItems unavailable", true);
        if (online == null) return CostOperationResult.failed("PLAYER_OFFLINE", true);
        String itemId = receipt.data().getOrDefault("item-id", ""); int amount = Integer.parseInt(receipt.data().getOrDefault("amount", "1"));
        var result = api.give(online, itemId, amount);
        return result.success() ? CostOperationResult.refunded() : CostOperationResult.failed(result.reason(), true);
    }
    @Override public boolean available() { return api() != null; }
    @Override public String unavailableReason() { return available() ? "" : "HexCustomItems API unavailable"; }
}
