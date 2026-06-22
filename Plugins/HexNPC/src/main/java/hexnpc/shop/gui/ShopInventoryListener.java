package hexnpc.shop.gui;

import hexnpc.shop.ShopService;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.Optional;

/**
 * Anuluje każdą interakcję z inventory shopu i przekazuje kliknięcie do
 * {@link ShopService}. ItemStack ze slotu nigdy nie jest źródłem prawdy
 * — akcje wyznaczamy na podstawie mapy slot → item z holdera i typu
 * widoku, więc podmiana ikon przez klienta nie nabierze nas na buy/sell.
 */
public final class ShopInventoryListener implements Listener {

    private final ShopService shopService;

    public ShopInventoryListener(ShopService shopService) {
        this.shopService = Objects.requireNonNull(shopService, "shopService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        ShopGuiHolder holder = holderOf(event.getView().getTopInventory().getHolder());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Każda interakcja przenosząca itemy między inventory musi być
        // zablokowana — także klik w slot gracza (np. shift-click z
        // ekwipunku do shopu).
        if (event.getClickedInventory() == null) {
            return;
        }
        // Jeśli klik trafił w dolny ekwipunek gracza, anulowanie eventu
        // wystarczy, by nic się nie przelało. Po prostu kończymy.
        if (event.getClickedInventory().getHolder() != holder) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        switch (holder.view()) {
            case MAIN -> handleMainClick(player, holder, slot);
            case DETAIL -> handleDetailClick(player, holder, slot, event.getClick(), event.getAction());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        ShopGuiHolder holder = holderOf(event.getView().getTopInventory().getHolder());
        if (holder == null) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                event.setResult(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // Nic do czyszczenia — blokada transakcji (busy) zwalniana jest
        // po zakończeniu ekonomii. Hook zostawiony pod przyszłe użycie.
    }

    private void handleMainClick(Player player, ShopGuiHolder holder, int slot) {
        String itemId = holder.itemSlotMap().get(slot);
        if (itemId == null) {
            return;
        }
        Shop shop = holder.shop();
        Optional<ShopItem> item = shop.item(itemId);
        if (item.isEmpty()) {
            return;
        }
        shopService.openDetail(player, shop, item.get());
    }

    private void handleDetailClick(Player player, ShopGuiHolder holder, int slot,
                                   ClickType click, InventoryAction action) {
        // Blokujemy akcje typu number-key / drop / swap, które mogłyby
        // przeciągnąć itemy. setCancelled na zewnątrz i tak temu
        // przeciwdziała, ale traktowanie tego jako intencji buy/sell
        // byłoby też błędne, więc ignorujemy.
        if (action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR
                || click == ClickType.SWAP_OFFHAND) {
            return;
        }
        Shop shop = holder.shop();
        if (slot == holder.backButtonSlot()) {
            shopService.back(player, shop);
            return;
        }
        ShopItem item = shop.item(holder.focusedItemId()).orElse(null);
        if (item == null) {
            return;
        }
        if (slot == holder.buyButtonSlot()) {
            shopService.buy(player, shop, item);
            return;
        }
        if (slot == holder.sellButtonSlot()) {
            shopService.sell(player, shop, item);
        }
    }

    private ShopGuiHolder holderOf(InventoryHolder holder) {
        if (holder instanceof ShopGuiHolder shopHolder) {
            return shopHolder;
        }
        return null;
    }
}
