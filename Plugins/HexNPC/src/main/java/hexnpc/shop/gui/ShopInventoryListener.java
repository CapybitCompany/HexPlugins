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
 * {@link ShopService}. Akcję wyznaczamy wyłącznie na podstawie map slotów z
 * holdera i typu widoku — zawartość slotu nigdy nie jest źródłem prawdy, więc
 * podmiana ikon przez klienta nie nabierze nas na buy/sell, a kliknięcia w
 * elementy nawigacji nigdy nie wywołują transakcji.
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
        if (event.getClickedInventory() == null) {
            return;
        }
        // Klik w dolny ekwipunek gracza — anulowanie wystarczy, nic nie robimy.
        if (event.getClickedInventory().getHolder() != holder) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        // Akcje przenoszące itemy nie mogą być traktowane jak intencja buy/sell.
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        if (action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR
                || click == ClickType.SWAP_OFFHAND) {
            return;
        }

        switch (holder.view()) {
            case MAIN -> handleMainClick(player, holder, slot);
            case DETAIL -> handleDetailClick(player, holder, slot);
            case CONFIRM -> handleConfirmClick(player, holder, slot);
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
        // Nic do czyszczenia — busy zwalniane po zakończeniu ekonomii.
    }

    private void handleMainClick(Player player, ShopGuiHolder holder, int slot) {
        Shop shop = holder.shop();
        // Nawigacja — tylko gdy odpowiednia strona istnieje.
        if (slot == holder.previousSlot() && holder.page() > 0) {
            shopService.openShop(player, shop.id(), holder.page() - 1);
            return;
        }
        if (slot == holder.nextSlot() && holder.page() < holder.totalPages() - 1) {
            shopService.openShop(player, shop.id(), holder.page() + 1);
            return;
        }
        // Item — otwarcie szczegółów z zapamiętaniem strony źródłowej.
        String itemId = holder.itemSlotMap().get(slot);
        if (itemId == null) {
            return;
        }
        Optional<ShopItem> item = shop.item(itemId);
        item.ifPresent(shopItem -> {
            if (shopItem.isDirectAction()) {
                shopService.executeItemAction(player, shop, shopItem);
            } else {
                shopService.openDetail(player, shop, shopItem, shopItem.amount(), holder.page());
            }
        });
    }

    private void handleDetailClick(Player player, ShopGuiHolder holder, int slot) {
        Shop shop = holder.shop();
        int originPage = holder.originPage();
        int selected = holder.selectedQuantity();

        if (slot == holder.backButtonSlot()) {
            shopService.back(player, shop, originPage);
            return;
        }
        ShopItem item = shop.item(holder.focusedItemId()).orElse(null);
        if (item == null) {
            return;
        }
        // Zmiana ilości przez preset — otwiera ponownie widok z nową ilością.
        Integer presetQty = holder.presetSlots().get(slot);
        if (presetQty != null) {
            shopService.openDetail(player, shop, item, presetQty, originPage);
            return;
        }
        if (slot == holder.customQuantitySlot() && holder.customQuantitySlot() >= 0) {
            shopService.requestCustomQuantity(player, shop, item, originPage, selected);
            return;
        }
        if (slot == holder.buyButtonSlot() && holder.buyButtonSlot() >= 0) {
            shopService.requestBuy(player, shop, item, selected, originPage);
            return;
        }
        if (slot == holder.sellButtonSlot() && holder.sellButtonSlot() >= 0) {
            shopService.requestSell(player, shop, item, selected, originPage);
            return;
        }
        if (slot == holder.sellAllButtonSlot() && holder.sellAllButtonSlot() >= 0) {
            shopService.requestSellAll(player, shop, item, originPage);
        }
    }

    private void handleConfirmClick(Player player, ShopGuiHolder holder, int slot) {
        Shop shop = holder.shop();
        ShopItem item = shop.item(holder.focusedItemId()).orElse(null);
        if (item == null) {
            return;
        }
        // Anuluj: wróć do widoku szczegółów, zachowując item, ilość i stronę.
        if (slot == holder.cancelSlot()) {
            shopService.cancelConfirmation(player, shop, item, holder.selectedQuantity(), holder.originPage());
            return;
        }
        // Potwierdź: wykonaj bezpośrednio wewnętrzny, ponownie walidujący tor.
        if (slot == holder.confirmSlot() && holder.confirmAction() != null) {
            shopService.confirmTransaction(player, shop, item, holder.confirmAction(),
                    holder.selectedQuantity(), holder.originPage());
        }
    }

    private ShopGuiHolder holderOf(InventoryHolder holder) {
        if (holder instanceof ShopGuiHolder shopHolder) {
            return shopHolder;
        }
        return null;
    }
}
