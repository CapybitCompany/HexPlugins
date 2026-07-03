package hex.towns.gui;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.config.TownsConfig;
import hex.towns.service.OperationResult;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Map;
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
    private final Map<UUID, Inventory> active = new ConcurrentHashMap<>();

    public TownRenameAnvilListener(Plugin plugin, HexApi api, TownsService service, TownsConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.config = config;
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(player, org.bukkit.event.inventory.InventoryType.ANVIL, "Zmień nazwę miasta");
        inventory.setItem(INPUT_SLOT, renamePaper("Nazwa miasta", "Wpisz nową nazwę"));
        active.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inventory) || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        if (active.get(player.getUniqueId()) != inventory) {
            return;
        }
        inventory.setRepairCost(0);
        String raw = inventory.getRenameText();
        String value = raw == null || raw.isBlank() ? "Nazwa miasta" : raw;
        event.setResult(renamePaper(value, "Kliknij, aby zatwierdzić zmianę"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory inventory = active.get(player.getUniqueId());
        if (inventory == null || event.getInventory() != inventory) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        if (!(event.getInventory() instanceof AnvilInventory anvil)) {
            return;
        }
        String name = anvil.getRenameText();
        if (name == null || name.isBlank()) {
            api.ui().send(player, "towns.rename.invalid", UiTokens.of("max", String.valueOf(config.maxNameLength())));
            return;
        }
        active.remove(player.getUniqueId());
        player.closeInventory();
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
        if (event.getPlayer() instanceof Player player) {
            active.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player player, OperationResult result) {
        api.ui().send(player, result.templateKey(), result.tokens());
    }

    private ItemStack renamePaper(String name, String lore) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(java.util.List.of("§7" + lore, "§8Maks. " + config.maxNameLength() + " znaków"));
            item.setItemMeta(meta);
        }
        return item;
    }
}
