package hexnpc.event;

import hexnpc.shop.ShopService;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class EventGuyMenuService {

    public static final int SIZE = 27;
    public static final int TICKET_SLOT = 11;
    public static final int EVENT_SLOT = 15;
    public static final String DEFAULT_TITLE = "&5Eventy";
    public static final String DEFAULT_SHOP_ID = "event_tickets";
    public static final String DEFAULT_EVENT_COMMAND = "event";

    @FunctionalInterface
    public interface InventoryFactory {
        Inventory create(InventoryHolder holder, int size, Component title);
    }

    private static volatile InventoryFactory inventoryFactory = Bukkit::createInventory;

    public static void setInventoryFactory(InventoryFactory factory) {
        inventoryFactory = factory != null ? factory : Bukkit::createInventory;
    }

    private final Supplier<ShopService> shopServiceSupplier;

    public EventGuyMenuService(Supplier<ShopService> shopServiceSupplier) {
        this.shopServiceSupplier = Objects.requireNonNull(shopServiceSupplier, "shopServiceSupplier");
    }

    public void open(Player player, String shopId, String eventCommand, String title) {
        String safeShopId = clean(shopId, DEFAULT_SHOP_ID);
        String safeCommand = clean(eventCommand, DEFAULT_EVENT_COMMAND);
        String safeTitle = clean(title, DEFAULT_TITLE);

        EventGuyMenuHolder holder = new EventGuyMenuHolder(safeShopId, safeCommand);
        Inventory inventory = inventoryFactory.create(holder, SIZE, LegacyFormat.component(safeTitle));
        holder.bind(inventory);
        fillBackground(inventory);
        inventory.setItem(TICKET_SLOT, button(Material.PAPER,
                "&aKup bilet",
                List.of("&7Otwiera sklep z biletami na event.", "&eKliknij, aby przejsc do sklepu.")));
        inventory.setItem(EVENT_SLOT, button(Material.BOOK,
                "&dInformacje o evencie",
                List.of("&7Wyswietla informacje z komendy &f/" + stripSlash(safeCommand) + "&7.",
                        "&eKliknij, aby sprawdzic event.")));
        player.openInventory(inventory);
    }

    public void handleClick(Player player, EventGuyMenuHolder holder, int slot) {
        if (slot == TICKET_SLOT) {
            openTicketShop(player, holder.shopId());
            return;
        }
        if (slot == EVENT_SLOT) {
            runEventCommand(player, holder.eventCommand());
        }
    }

    private void openTicketShop(Player player, String shopId) {
        ShopService shopService = shopServiceSupplier.get();
        if (shopService == null) {
            player.sendMessage(LegacyFormat.component("&cSklep biletow jest obecnie niedostepny."));
            return;
        }
        player.closeInventory();
        shopService.openShop(player, shopId);
    }

    private void runEventCommand(Player player, String command) {
        String rendered = LegacyFormat.replace(command, "<player>", player.getName()).trim();
        if (rendered.isEmpty()) {
            return;
        }
        player.closeInventory();
        player.performCommand(stripSlash(rendered));
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String stripSlash(String command) {
        String out = command == null ? "" : command.trim();
        return out.startsWith("/") ? out.substring(1) : out;
    }

    private static void fillBackground(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.addItemFlags(ItemFlag.values());
            try {
                meta.setHideTooltip(true);
            } catch (Throwable ignored) {
                // Older API shims can miss this Paper method.
            }
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(name));
            meta.lore(lore.stream().map(LegacyFormat::component).toList());
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
