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
 * Holder przechowuje referencję do shopu, typ widoku (główna siatka
 * vs. szczegóły itemu), opcjonalne id wyróżnionego itemu oraz mapę
 * slot → item-id używaną przez router kliknięć. Stan ekwipunku gracza
 * nigdy nie jest źródłem prawdy — zawsze patrzymy w holder.
 */
public final class ShopGuiHolder implements InventoryHolder {

    public enum View {
        MAIN,
        DETAIL
    }

    private final Shop shop;
    private final View view;
    private final String focusedItemId;
    private final Map<Integer, String> itemSlotMap;
    private final int buyButtonSlot;
    private final int sellButtonSlot;
    private final int backButtonSlot;

    private Inventory inventory;

    public ShopGuiHolder(Shop shop, View view, String focusedItemId,
                         Map<Integer, String> itemSlotMap,
                         int buyButtonSlot, int sellButtonSlot, int backButtonSlot) {
        this.shop = Objects.requireNonNull(shop, "shop");
        this.view = Objects.requireNonNull(view, "view");
        this.focusedItemId = focusedItemId;
        this.itemSlotMap = itemSlotMap == null ? Map.of() : Map.copyOf(itemSlotMap);
        this.buyButtonSlot = buyButtonSlot;
        this.sellButtonSlot = sellButtonSlot;
        this.backButtonSlot = backButtonSlot;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        // Kontrakt Bukkita: nigdy nie zwracać null. Jeśli nie wykonano
        // bind() (defensywne — bind() jest wołane natychmiast), rzucamy.
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

    public String focusedItemId() {
        return focusedItemId;
    }

    public Map<Integer, String> itemSlotMap() {
        return itemSlotMap;
    }

    public int buyButtonSlot() {
        return buyButtonSlot;
    }

    public int sellButtonSlot() {
        return sellButtonSlot;
    }

    public int backButtonSlot() {
        return backButtonSlot;
    }

    public InventoryType inventoryType() {
        return InventoryType.CHEST;
    }
}
