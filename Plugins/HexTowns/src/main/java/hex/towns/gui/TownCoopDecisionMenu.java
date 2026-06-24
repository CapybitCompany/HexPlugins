package hex.towns.gui;

import hex.core.api.HexApi;
import hex.towns.service.OperationResult;
import hex.towns.service.TownsService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class TownCoopDecisionMenu implements Listener {
    private static final int ACCEPT_SLOT = 20;
    private static final int REJECT_SLOT = 24;
    private static final int BACK_SLOT = 40;

    private final Plugin plugin;
    private final HexApi api;
    private final TownsService service;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public TownCoopDecisionMenu(Plugin plugin, HexApi api, TownsService service) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
    }

    public void openRequestDecision(Player owner, UUID targetId, String targetName) {
        String safeName = safeName(targetId, targetName);
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.REQUEST_DECISION, targetId, safeName), 45,
                mini.deserialize("<dark_gray>COOP: " + safeName));
        fill(inv);
        inv.setItem(13, item(Material.PLAYER_HEAD, "<aqua>Prośba COOP: " + safeName + "</aqua>", List.of(
                "<gray>Decydujesz, czy przyjąć tego gracza do miasta.</gray>",
                "<yellow>Opcja dostępna tylko dla właściciela miasta.</yellow>"
        )));
        inv.setItem(ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Przyjmij do miasta</green>", List.of("<gray>Gracz dołączy jako COOP.</gray>")));
        inv.setItem(REJECT_SLOT, item(Material.RED_CONCRETE, "<red>Odrzuć prośbę</red>", List.of("<gray>Prośba zostanie usunięta.</gray>")));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do menu COOP.</gray>")));
        owner.openInventory(inv);
    }

    public void openMemberKick(Player owner, UUID targetId, String targetName) {
        String safeName = safeName(targetId, targetName);
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.MEMBER_KICK, targetId, safeName), 45,
                mini.deserialize("<dark_gray>COOP: usuń " + safeName));
        fill(inv);
        inv.setItem(13, item(Material.PLAYER_HEAD, "<aqua>Członek miasta: " + safeName + "</aqua>", List.of(
                "<gray>Możesz usunąć gracza z miasta.</gray>",
                "<red>Usunięcie zgłasza pełny reset statystyk/progresu</red>",
                "<red>i czyści aktualne itemy gracza, jeśli jest online.</red>"
        )));
        inv.setItem(REJECT_SLOT, item(Material.RED_CONCRETE, "<red>Usuń gracza z miasta</red>", List.of("<gray>Gracz straci dostęp do miasta.</gray>")));
        inv.setItem(ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Zostaw gracza</green>", List.of("<gray>Anuluj i wróć do menu COOP.</gray>")));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do menu COOP.</gray>")));
        owner.openInventory(inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TownCoopDecisionMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TownCoopDecisionMenuHolder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) return;
        if (slot == BACK_SLOT || (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_KICK && slot == ACCEPT_SLOT)) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("towncoop"));
            return;
        }
        CompletableFuture<OperationResult> action = null;
        if (holder.action() == TownCoopDecisionMenuHolder.Action.REQUEST_DECISION) {
            if (slot == ACCEPT_SLOT) action = service.acceptCoopRequest(player, holder.targetId(), holder.targetName());
            if (slot == REJECT_SLOT) action = service.rejectCoopRequest(player, holder.targetId(), holder.targetName());
        } else if (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_KICK && slot == REJECT_SLOT) {
            action = service.kickCoopMember(player, holder.targetId(), holder.targetName());
        }
        if (action == null) return;
        player.closeInventory();
        handle(player, action);
    }

    private void handle(Player player, CompletableFuture<OperationResult> future) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "HexTowns COOP GUI action failed for " + player.getName(), cause);
                api.ui().send(player, "towns.error.db", hex.core.api.ui.UiTokens.of("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                return;
            }
            api.ui().send(player, result.templateKey(), result.tokens());
            player.performCommand("towncoop");
        }));
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(mini.deserialize(name));
            java.util.List<net.kyori.adventure.text.Component> cleanLore = lore == null ? java.util.List.of() : lore.stream()
                    .filter(line -> line != null && !line.isBlank())
                    .map(mini::deserialize)
                    .toList();
            meta.lore(cleanLore.isEmpty() ? null : cleanLore);
            addItemFlags(meta, "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP");
            if ((name == null || name.isBlank()) && cleanLore.isEmpty()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void addItemFlags(ItemMeta meta, String... flagNames) {
        try {
            Class<?> itemFlagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            java.util.List<Object> flags = new java.util.ArrayList<>();
            for (String flagName : flagNames) {
                try {
                    flags.add(java.lang.Enum.valueOf((Class) itemFlagClass, flagName));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (flags.isEmpty()) return;
            Object flagsArray = java.lang.reflect.Array.newInstance(itemFlagClass, flags.size());
            for (int i = 0; i < flags.size(); i++) java.lang.reflect.Array.set(flagsArray, i, flags.get(i));
            meta.getClass().getMethod("addItemFlags", flagsArray.getClass()).invoke(meta, flagsArray);
        } catch (Throwable ignored) {
        }
    }

    private void hideTooltip(ItemMeta meta) {
        try {
            meta.getClass().getMethod("setHideTooltip", boolean.class).invoke(meta, true);
        } catch (Throwable ignored) {
        }
    }


    private String safeName(UUID targetId, String targetName) {
        return targetName == null || targetName.isBlank() ? targetId.toString().substring(0, 8) : targetName;
    }
}
