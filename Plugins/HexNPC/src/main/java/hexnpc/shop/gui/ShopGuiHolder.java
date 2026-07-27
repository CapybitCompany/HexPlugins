package hexnpc.shop.gui;

import hexnpc.shop.model.Shop;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Własny InventoryHolder oznaczający dany inventory jako widok shopu
 * HexNPC. Listenery rozpoznają widok shopu przez
 * {@code holder instanceof ShopGuiHolder}, bez porównywania tytułu —
 * dzięki temu zmiana napisu w GUI nie psuje routingu zdarzeń.
 *
 * <p>Holder jest jedynym źródłem prawdy o routingu kliknięć i o stanie
 * sesji GUI (bieżąca strona w widoku głównym; wybrana ilość i strona
 * źródłowa w widoku szczegółów). Zawartość slotów nigdy nie decyduje o
 * akcji — patrzymy wyłącznie na mapy slotów z holdera. Wybrana ilość
 * należy do konkretnej sesji GUI i nie wycieka na innych graczy ani sklepy.
 */
public final class ShopGuiHolder implements InventoryHolder {

    public enum View {
        MAIN,
        DETAIL,
        CONFIRM
    }

    private final Shop shop;
    private final View view;

    // Widok główny (MAIN)
    private final int page;
    private final int totalPages;
    private final Map<Integer, String> itemSlotMap;
    private final int previousSlot;
    private final int nextSlot;
    private final int pageInfoSlot;

    // Widok szczegółów (DETAIL)
    private final String focusedItemId;
    private final int selectedQuantity;
    private final int originPage;
    private final Map<Integer, Integer> presetSlots; // slot -> ilość
    private final int customQuantitySlot;
    private final int buyButtonSlot;
    private final int sellButtonSlot;
    private final int sellAllButtonSlot;
    private final int backButtonSlot;
    private final int previewSlot;

    // Widok potwierdzenia (CONFIRM)
    private final ConfirmAction confirmAction;
    private final int confirmSlot;
    private final int cancelSlot;

    private Inventory inventory;

    private ShopGuiHolder(Shop shop, View view, int page, int totalPages,
                          Map<Integer, String> itemSlotMap, int previousSlot, int nextSlot, int pageInfoSlot,
                          String focusedItemId, int selectedQuantity, int originPage,
                          Map<Integer, Integer> presetSlots, int customQuantitySlot,
                          int buyButtonSlot, int sellButtonSlot, int sellAllButtonSlot,
                          int backButtonSlot, int previewSlot,
                          ConfirmAction confirmAction, int confirmSlot, int cancelSlot) {
        this.shop = Objects.requireNonNull(shop, "shop");
        this.view = Objects.requireNonNull(view, "view");
        this.page = page;
        this.totalPages = totalPages;
        this.itemSlotMap = itemSlotMap == null ? Map.of() : Map.copyOf(itemSlotMap);
        this.previousSlot = previousSlot;
        this.nextSlot = nextSlot;
        this.pageInfoSlot = pageInfoSlot;
        this.focusedItemId = focusedItemId;
        this.selectedQuantity = selectedQuantity;
        this.originPage = originPage;
        this.presetSlots = presetSlots == null ? Map.of() : Map.copyOf(presetSlots);
        this.customQuantitySlot = customQuantitySlot;
        this.buyButtonSlot = buyButtonSlot;
        this.sellButtonSlot = sellButtonSlot;
        this.sellAllButtonSlot = sellAllButtonSlot;
        this.backButtonSlot = backButtonSlot;
        this.previewSlot = previewSlot;
        this.confirmAction = confirmAction;
        this.confirmSlot = confirmSlot;
        this.cancelSlot = cancelSlot;
    }

    public static ShopGuiHolder main(Shop shop, int page, int totalPages,
                                     Map<Integer, String> itemSlotMap,
                                     int previousSlot, int nextSlot, int pageInfoSlot) {
        return new ShopGuiHolder(shop, View.MAIN, page, totalPages, itemSlotMap,
                previousSlot, nextSlot, pageInfoSlot,
                null, 0, page, Map.of(), -1, -1, -1, -1, -1, -1,
                null, -1, -1);
    }

    public static ShopGuiHolder detail(Shop shop, String focusedItemId, int selectedQuantity,
                                       int originPage, Map<Integer, Integer> presetSlots,
                                       int customQuantitySlot, int buyButtonSlot, int sellButtonSlot,
                                       int sellAllButtonSlot, int backButtonSlot, int previewSlot) {
        return new ShopGuiHolder(shop, View.DETAIL, originPage, 1, Map.of(), -1, -1, -1,
                focusedItemId, selectedQuantity, originPage, presetSlots, customQuantitySlot,
                buyButtonSlot, sellButtonSlot, sellAllButtonSlot, backButtonSlot, previewSlot,
                null, -1, -1);
    }

    public static ShopGuiHolder confirm(Shop shop, String focusedItemId, ConfirmAction action,
                                        int quantity, int originPage,
                                        int confirmSlot, int cancelSlot, int previewSlot) {
        return new ShopGuiHolder(shop, View.CONFIRM, originPage, 1, Map.of(), -1, -1, -1,
                focusedItemId, quantity, originPage, Map.of(), -1, -1, -1, -1, -1, previewSlot,
                action, confirmSlot, cancelSlot);
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("ShopGuiHolder accessed before its inventory was bound");
        }
        return inventory;
    }

    public Shop shop() {
        return shop;
    }

    public View view() {
        return view;
    }

    public int page() {
        return page;
    }

    public int totalPages() {
        return totalPages;
    }

    public Map<Integer, String> itemSlotMap() {
        return itemSlotMap;
    }

    public int previousSlot() {
        return previousSlot;
    }

    public int nextSlot() {
        return nextSlot;
    }

    public int pageInfoSlot() {
        return pageInfoSlot;
    }

    public String focusedItemId() {
        return focusedItemId;
    }

    public int selectedQuantity() {
        return selectedQuantity;
    }

    public int originPage() {
        return originPage;
    }

    public Map<Integer, Integer> presetSlots() {
        return presetSlots;
    }

    public int customQuantitySlot() {
        return customQuantitySlot;
    }

    public int buyButtonSlot() {
        return buyButtonSlot;
    }

    public int sellButtonSlot() {
        return sellButtonSlot;
    }

    public int sellAllButtonSlot() {
        return sellAllButtonSlot;
    }

    public int backButtonSlot() {
        return backButtonSlot;
    }

    public int previewSlot() {
        return previewSlot;
    }

    public ConfirmAction confirmAction() {
        return confirmAction;
    }

    public int confirmSlot() {
        return confirmSlot;
    }

    public int cancelSlot() {
        return cancelSlot;
    }

    public InventoryType inventoryType() {
        return InventoryType.CHEST;
    }
}
