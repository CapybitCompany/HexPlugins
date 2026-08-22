package hex.gui.menu;

import hex.gui.HexGUIPlugin;
import hex.gui.config.HubConfig;
import hex.gui.config.MenuEntry;
import hex.gui.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HubMenu implements Listener {
    private final HexGUIPlugin plugin;

    public HubMenu(HexGUIPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        HubConfig config = plugin.hubConfig();
        Inventory inventory = Bukkit.createInventory(new HubMenuHolder(), config.size(), Text.color(player, plugin, config.title()));
        fill(inventory, player, config);

        for (MenuEntry entry : config.entries()) {
            boolean available = isAvailable(entry, player);
            inventory.setItem(entry.slot(), createEntryIcon(player, config, entry, available));
        }

        player.openInventory(inventory);
        play(player, config.openSound(), 0.7f, 1.1f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HubMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        HubConfig config = plugin.hubConfig();
        MenuEntry entry = config.entriesBySlot().get(event.getRawSlot());
        if (entry == null) return;
        if (entry.action() == MenuEntry.Action.NONE) return;

        if (!entry.permission().isBlank() && !player.hasPermission(entry.permission())) {
            Text.send(player, plugin, config.noPermissionMessage());
            play(player, config.unavailableSound(), 0.7f, 0.8f);
            return;
        }

        if (!isAvailable(entry, player)) {
            Text.send(player, plugin, config.unavailableMessage());
            play(player, config.unavailableSound(), 0.7f, 0.8f);
            return;
        }

        if (entry.closeOnClick()) player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> execute(player, entry));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HubMenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    public boolean isAvailable(MenuEntry entry, Player player) {
        if (entry == null || !entry.enabled()) return false;
        if (entry.action() == MenuEntry.Action.NONE) return true;
        if (entry.command().isBlank()) return false;
        for (String requiredPlugin : entry.requiredPlugins()) {
            Plugin target = plugin.getServer().getPluginManager().getPlugin(requiredPlugin);
            if (target == null || !target.isEnabled()) return false;
        }

        String root = entry.commandRoot();
        if (root.isBlank()) return false;
        PluginCommand command = plugin.getServer().getPluginCommand(root);
        if (command == null) return false;
        Plugin owner = command.getPlugin();
        if (owner == null || !owner.isEnabled()) return false;

        return true;
    }

    private void execute(Player player, MenuEntry entry) {
        HubConfig config = plugin.hubConfig();
        if (entry.action() == MenuEntry.Action.NONE) return;

        // Re-check immediately before dispatch. A plugin may have been disabled after GUI render/click.
        if (!isAvailable(entry, player)) {
            failAndReturn(player, config, config.unavailableMessage(), config.unavailableSound());
            return;
        }

        String command = applyCommandPlaceholders(player, entry.command());
        try {
            boolean accepted = switch (entry.runAs()) {
                case PLAYER -> player.performCommand(command);
                case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            };
            if (!accepted) {
                plugin.getLogger().warning("Komenda wpisu '" + entry.id() + "' nie została obsłużona: /" + command);
                failAndReturn(player, config, config.commandFailedMessage(), config.errorSound());
            }
        } catch (Throwable throwable) {
            plugin.getLogger().severe("Błąd podczas wykonywania wpisu '" + entry.id() + "' (/" + command + "): " + throwable);
            failAndReturn(player, config, config.commandFailedMessage(), config.errorSound());
        }
    }

    private void failAndReturn(Player player, HubConfig config, String message, Sound sound) {
        Text.send(player, plugin, message);
        play(player, sound, 0.8f, 0.8f);
        if (player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) open(player);
            });
        }
    }

    private ItemStack createEntryIcon(Player player, HubConfig config, MenuEntry entry, boolean available) {
        ItemStack stack = entry.icon().create(plugin);
        if (stack == null || stack.getType().isAir()) stack = new ItemStack(Material.STONE);
        stack.setAmount(1);

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(Text.color(player, plugin, entry.name()));

        List<String> lore = new ArrayList<>(Text.color(player, plugin, entry.lore()));
        if (!available) lore.addAll(Text.color(player, plugin, config.unavailableLore()));
        meta.setLore(lore.isEmpty() ? null : lore);
        hideVanillaNoise(meta);
        stack.setItemMeta(meta);
        return stack;
    }

    private void fill(Inventory inventory, Player player, HubConfig config) {
        ItemStack filler = new ItemStack(config.fillerMaterial());
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            meta.setLore(null);
            hideVanillaNoise(meta);
            if (config.fillerHideTooltip()) hideTooltip(meta);
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private static String applyCommandPlaceholders(Player player, String command) {
        return command
                .replace("{player}", player.getName())
                .replace("%player%", player.getName());
    }

    private static void hideVanillaNoise(ItemMeta meta) {
        try {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        } catch (Throwable ignored) {
        }
        try {
            ItemFlag additional = ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP");
            meta.addItemFlags(additional);
        } catch (Throwable ignored) {
        }
    }

    private static void hideTooltip(ItemMeta meta) {
        // Paper 1.21.x: natywne hide_tooltip ukrywa cały tooltip, łącznie z pustą nazwą.
        // Wywołujemy metodę bezpośrednio przez API zamiast refleksji na klasie implementacji
        // (CraftMetaItem może być niepubliczna, przez co refleksyjne invoke potrafi się nie udać).
        meta.setHideTooltip(true);
    }

    private static void play(Player player, Sound sound, float volume, float pitch) {
        if (sound == null) return;
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Throwable ignored) {
        }
    }
}
