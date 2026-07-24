package hexcustomitems.ui;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Baut das paginierte Give-Menü auf und liefert Navigation/Metadaten.
 * Nav-Buttons werden über einen eigenen PDC-Key markiert.
 */
public final class MenuService {

    public static final String ACTION_PREV = "prev";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_INFO = "info";

    private final JavaPlugin plugin;
    private final CustomItemRegistryService registryService;
    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final NamespacedKey actionKey;

    public MenuService(
            JavaPlugin plugin,
            CustomItemRegistryService registryService,
            Supplier<HexCustomItemsConfig> configSupplier
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.actionKey = new NamespacedKey(plugin, "menu_action");
    }

    /** Öffnet das Menü im nächsten Tick - sicher aus einem InventoryClickEvent heraus. */
    public void openLater(Player viewer, UUID targetId, int requestedPage) {
        plugin.getServer().getScheduler().runTask(plugin, () -> open(viewer, targetId, requestedPage));
    }

    public void open(Player viewer, UUID targetId, int requestedPage) {
        List<CustomItemDefinition> definitions = new ArrayList<>(registryService.allItems().values());
        int pageCount = Math.max(1, (int) Math.ceil(definitions.size() / (double) ItemsMenu.CONTENT_SLOTS));
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        ItemsMenu menu = new ItemsMenu(page, pageCount, targetId);
        Inventory inventory = Bukkit.createInventory(menu, ItemsMenu.SIZE, buildTitle(page, pageCount, viewer, targetId));
        menu.attach(inventory);

        OfflinePlayer papiContext = resolvePapiContext(viewer, targetId);
        int start = page * ItemsMenu.CONTENT_SLOTS;
        for (int i = 0; i < ItemsMenu.CONTENT_SLOTS && start + i < definitions.size(); i++) {
            inventory.setItem(i, registryService.createItem(definitions.get(start + i), 1, papiContext));
        }

        if (page > 0) {
            inventory.setItem(ItemsMenu.SLOT_PREV, navButton(Material.ARROW, "<yellow>« Poprzednia strona", ACTION_PREV));
        }
        if (page < pageCount - 1) {
            inventory.setItem(ItemsMenu.SLOT_NEXT, navButton(Material.ARROW, "<yellow>Następna strona »", ACTION_NEXT));
        }
        inventory.setItem(ItemsMenu.SLOT_INFO, infoButton(page, pageCount, viewer, targetId));

        menu.open(viewer);
    }

    /** Liest die Nav-Aktion eines Buttons; {@code null} = normales Item bzw. kein Button. */
    public String readAction(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private Component buildTitle(int page, int pageCount, Player viewer, UUID targetId) {
        String base = configSupplier.get().menuTitle();
        String pageInfo = " <gray>(<yellow>" + (page + 1) + "<gray>/<yellow>" + pageCount + "<gray>)";
        String targetInfo = "";
        if (targetId != null && !targetId.equals(viewer.getUniqueId())) {
            targetInfo = " <gray>→ <yellow>" + targetName(targetId, viewer);
        }
        return TextUtil.parse(base + pageInfo + targetInfo);
    }

    private ItemStack navButton(Material material, String name, String action) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.itemName(name, Map.of(), null));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack infoButton(int page, int pageCount, Player viewer, UUID targetId) {
        String forWhom = (targetId == null || targetId.equals(viewer.getUniqueId()))
                ? viewer.getName()
                : targetName(targetId, viewer);
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.itemName("<gold>Otrzymuje: <yellow>" + forWhom, Map.of(), null));
            meta.lore(TextUtil.itemLore(
                    List.of("<gray>Strona <yellow>" + (page + 1) + "<gray>/<yellow>" + pageCount,
                            "<gray>Kliknij przedmiot, aby dać <yellow>1",
                            "<gray>Shift+klik: <yellow>" + configSupplier.get().menuShiftGiveAmount()),
                    Map.of(), null));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, ACTION_INFO);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private OfflinePlayer resolvePapiContext(Player viewer, UUID targetId) {
        if (targetId == null) {
            return viewer;
        }
        Player target = Bukkit.getPlayer(targetId);
        return target != null ? target : viewer;
    }

    private String targetName(UUID targetId, Player viewer) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            return target.getName();
        }
        String name = Bukkit.getOfflinePlayer(targetId).getName();
        return name != null ? name : viewer.getName();
    }
}
