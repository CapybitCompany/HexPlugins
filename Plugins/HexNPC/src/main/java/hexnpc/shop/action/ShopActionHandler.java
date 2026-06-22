package hexnpc.shop.action;

import hexnpc.action.NpcActionHandler;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.shop.ShopService;
import hexnpc.util.LegacyFormat;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Handler akcji NPC otwierający shop HexNPC. Użycie w YAML:
 *   actions:
 *     on-click:
 *       - { type: npc-shop, shop: starter }
 *
 * Trzymamy Supplier zamiast bezpośredniej referencji ShopService, dzięki
 * czemu handler przeżywa reload HexNPC (instancja serwisu może się
 * zmienić, ale wpis w action registry pozostaje ten sam).
 */
public final class ShopActionHandler implements NpcActionHandler {

    public static final String ID = "npc-shop";

    private final Supplier<ShopService> serviceSupplier;

    public ShopActionHandler(Supplier<ShopService> serviceSupplier) {
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void execute(Player player, NpcDefinition npc, NpcAction action) {
        ShopService service = serviceSupplier.get();
        if (service == null) {
            player.sendMessage(LegacyFormat.component("&cSklepy są obecnie niedostępne."));
            return;
        }
        String shopId = action.asString("shop", "").trim();
        if (shopId.isEmpty()) {
            player.sendMessage(LegacyFormat.component("&cAkcja npc-shop wymaga klucza &fshop&c."));
            return;
        }
        service.openShop(player, shopId);
    }
}
