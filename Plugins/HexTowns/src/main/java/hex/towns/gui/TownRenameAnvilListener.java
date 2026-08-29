package hex.towns.gui;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.config.TownsConfig;
import hex.towns.service.OperationResult;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class TownRenameAnvilListener implements Listener {
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 2;

    private final Plugin plugin;
    private final HexApi api;
    private final TownsService service;
    private volatile TownsConfig config;
    private final NamespacedKey technicalItemKey;
    // Sesja jest per gracz. Nie przechowujemy referencji Inventory, ponieważ Paper
    // może zwracać różne wrappery widoku kowadła pomiędzy eventami.
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public TownRenameAnvilListener(Plugin plugin, HexApi api, TownsService service, TownsConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.config = config;
        this.technicalItemKey = new NamespacedKey(plugin, "town_rename_technical");
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    public void open(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        active.remove(playerId);
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            // Używamy natywnego widoku kowadła gracza zamiast Bukkit.createInventory(ANVIL).
            // Paper 1.21.1 udostępnia AnvilView#getRenameText()/setRepairCost(), co pozwala
            // obsłużyć wpisywany tekst bez refleksji i bez polegania na wrapperze Inventory.
            // Aktywujemy sesję przed openAnvil(), ponieważ Paper może wywołać pierwszy
            // PrepareAnvilEvent jeszcze w trakcie otwierania widoku.
            active.add(playerId);
            org.bukkit.inventory.InventoryView opened = player.openAnvil(null, true);
            if (!(opened instanceof AnvilView view)) {
                active.remove(playerId);
                plugin.getLogger().warning("Nie udało się otworzyć natywnego kowadła zmiany nazwy dla " + player.getName());
                return;
            }

            AnvilInventory inventory = view.getTopInventory();
            inventory.setItem(INPUT_SLOT, renamePaper("Nazwa miasta", "Wpisz nową nazwę"));
            view.setRepairCost(0);
        });
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!active.contains(player.getUniqueId())) return;

        AnvilView view = event.getView();
        view.setRepairCost(0);
        String raw = view.getRenameText();
        String value = raw == null || raw.isBlank() ? "Nazwa miasta" : raw.trim();
        event.setResult(renamePaper(value, "Kliknij, aby zatwierdzić zmianę"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView() instanceof AnvilView view)) return;
        if (!active.contains(player.getUniqueId())) return;

        // Kowadło służy wyłącznie jako pole tekstowe. Nie pozwalamy przenosić jego
        // technicznego PAPER-u ani wyniku do ekwipunku.
        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) return;

        String name = submittedName(view, event.getCurrentItem());
        if (name == null || name.isBlank()) {
            api.ui().send(player, "towns.rename.invalid", UiTokens.of("max", String.valueOf(config.maxNameLength())));
            return;
        }

        active.remove(player.getUniqueId());
        clearTechnicalAnvil(view);
        player.closeInventory();
        scheduleTechnicalPurge(player);
        service.renameTown(player, name).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "HexTowns anvil rename failed for " + player.getName(), cause);
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                api.ui().send(player, "towns.error.db", UiTokens.of("error", message));
                return;
            }
            send(player, result);
        }));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView() instanceof AnvilView view && active.remove(player.getUniqueId())) {
            clearTechnicalAnvil(view);
            scheduleTechnicalPurge(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        purgeTechnicalItems(event.getPlayer());
    }

    private String submittedName(AnvilView view, ItemStack clickedResult) {
        String raw = view.getRenameText();
        if (raw != null) {
            String value = raw.trim();
            if (!value.isBlank() && !"Nazwa miasta".equalsIgnoreCase(value)) return value;
        }

        ItemStack input = view.getTopInventory().getItem(INPUT_SLOT);
        String inputName = displayName(input);
        if (!inputName.isBlank() && !"Nazwa miasta".equalsIgnoreCase(inputName)) return inputName;

        String resultName = displayName(clickedResult);
        if (!resultName.isBlank() && !"Nazwa miasta".equalsIgnoreCase(resultName)) return resultName;
        return raw == null ? "" : raw.trim();
    }

    private String displayName(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return "";
        String value = meta.getDisplayName();
        return value == null ? "" : value.trim();
    }

    private void send(Player player, OperationResult result) {
        api.ui().send(player, result.templateKey(), result.tokens());
    }

    private void clearTechnicalAnvil(AnvilView view) {
        if (view == null) return;
        AnvilInventory top = view.getTopInventory();
        for (int slot : new int[]{0, 1, 2}) {
            if (isTechnical(top.getItem(slot))) top.setItem(slot, null);
        }
    }

    private void scheduleTechnicalPurge(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) purgeTechnicalItems(player);
        });
    }

    private void purgeTechnicalItems(Player player) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isTechnical(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
        if (isTechnical(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private boolean isTechnical(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(technicalItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private ItemStack renamePaper(String name, String lore) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(java.util.List.of("§7" + lore, "§8Maks. " + config.maxNameLength() + " znaków"));
            meta.getPersistentDataContainer().set(technicalItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
