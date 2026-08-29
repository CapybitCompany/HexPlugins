package hex.towns.gui;

import hex.core.api.HexApi;
import hex.towns.api.TownPermission;
import hex.towns.model.Town;
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
    private static final int PERMISSIONS_SLOT = 22;

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
                mini.deserialize("<dark_gray>Dołączenie: " + safeName));
        fill(inv);
        inv.setItem(13, item(Material.PLAYER_HEAD, "<aqua>Prośba o dołączenie: " + safeName + "</aqua>", List.of(
                rankLore(targetId),
                "",
                "<gray>Decydujesz, czy przyjąć tego gracza do miasta.</gray>",
                "<yellow>Opcja dostępna tylko dla właściciela miasta.</yellow>"
        )));
        inv.setItem(ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Przyjmij do miasta</green>", List.of("<gray>Gracz zostanie członkiem miasta.</gray>")));
        inv.setItem(REJECT_SLOT, item(Material.RED_CONCRETE, "<red>Odrzuć prośbę</red>", List.of("<gray>Prośba zostanie usunięta.</gray>")));
        inv.setItem(31, item(Material.SCAFFOLDING, "<yellow>Wpływ na progresję miasta</yellow>", List.of(
                "<gray>Przyjęcie kolejnego gracza może zwiększyć</gray>",
                "<gray>wymagania aktywnych kolekcji.</gray>",
                "<gray>Może przez to zwiększyć również koszty</gray>",
                "<gray>kolejnych ulepszeń minionów.</gray>",
                "",
                "<gold>Późniejsze odejście gracza nie obniży</gold>",
                "<gold>już wcześniej zwiększonego aktywnego celu.</gold>"
        )));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do menu graczy.</gray>")));
        owner.openInventory(inv);
    }

    public void openMemberKick(Player owner, UUID targetId, String targetName) {
        String safeName = safeName(targetId, targetName);
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.MEMBER_KICK, targetId, safeName), 45,
                mini.deserialize("<dark_gray>Członek: usuń " + safeName));
        fill(inv);
        inv.setItem(13, item(Material.PLAYER_HEAD, "<aqua>Członek miasta: " + safeName + "</aqua>", List.of(
                rankLore(targetId),
                "",
                "<gray>Możesz zarządzać dostępem tego gracza albo usunąć go z miasta.</gray>",
                "<yellow>Kick usuwa tylko przedmioty przypisane do tego miasta.</yellow>",
                "<gray>Prywatne EQ, Ender Chest i XP pozostają bez zmian.</gray>"
        )));
        inv.setItem(PERMISSIONS_SLOT, item(Material.COMPARATOR, "<aqua>Uprawnienia członka</aqua>", List.of("<gray>Ustaw dostęp do budowania, skrzyń, minionów, maszyn i wypłat z banku.</gray>")));
        inv.setItem(REJECT_SLOT, item(Material.RED_CONCRETE, "<red>Usuń gracza z miasta</red>", List.of("<gray>Gracz straci dostęp do miasta i town-bound assety.</gray>")));
        inv.setItem(ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Zostaw gracza</green>", List.of("<gray>Anuluj i wróć do menu graczy.</gray>")));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do menu graczy.</gray>")));
        owner.openInventory(inv);
    }

    public void openMemberKickAsAdmin(Player admin, Town town, UUID targetId, String targetName) {
        if (admin == null || town == null || !admin.hasPermission("hextowns.admin")) return;
        String safeName = safeName(targetId, targetName);
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.MEMBER_KICK, targetId, safeName, town.id(), true), 45,
                mini.deserialize("<dark_gray>DEV właściciel: " + safeName));
        fill(inv);
        inv.setItem(13, item(Material.PLAYER_HEAD, "<light_purple>DEV: członek " + safeName + "</light_purple>", List.of(
                rankLore(targetId),
                "",
                "<gray>Administracyjny widok właściciela miasta.</gray>",
                "<gray>Możesz naprawić uprawnienia członka.</gray>",
                "<dark_gray>Kick pozostaje zablokowany, aby uniknąć przypadkowej utraty danych.</dark_gray>"
        )));
        inv.setItem(PERMISSIONS_SLOT, item(Material.COMPARATOR, "<aqua>Uprawnienia członka</aqua>", List.of("<gray>Zmień uprawnienia realnego członka jako administrator.</gray>")));
        inv.setItem(REJECT_SLOT, item(Material.GRAY_CONCRETE, "<gray>Usunięcie gracza zablokowane w DEV</gray>", List.of("<dark_gray>Użyj zwykłej ścieżki właściciela lub narzędzia administracyjnego świadomie.</dark_gray>")));
        inv.setItem(ACCEPT_SLOT, item(Material.LIME_CONCRETE, "<green>Powrót</green>", List.of("<gray>Wróć do menu graczy.</gray>")));
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do menu graczy.</gray>")));
        admin.openInventory(inv);
    }

    public void openMemberPermissions(Player owner, UUID targetId, String targetName) {
        String safeName = safeName(targetId, targetName);
        var town = service.townIdOf(owner.getUniqueId()).flatMap(service::findTown).orElse(null);
        if (town == null || !service.isOwner(owner.getUniqueId(), town.id())) {
            api.ui().send(owner, "towns.error.not-owner");
            return;
        }
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.MEMBER_PERMISSIONS, targetId, safeName), 45,
                mini.deserialize("<dark_gray>Uprawnienia: " + safeName));
        fill(inv);
        var values = service.permissionsOf(targetId, town.id());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        TownPermission[] permissions = TownPermission.values();
        for (int i = 0; i < permissions.length && i < slots.length; i++) {
            TownPermission permission = permissions[i];
            boolean allowed = values.getOrDefault(permission, true);
            inv.setItem(slots[i], item(allowed ? Material.LIME_DYE : Material.GRAY_DYE,
                    (allowed ? "<green>" : "<red>") + permissionLabel(permission) + (allowed ? "</green>" : "</red>"),
                    List.of("<gray>Status: " + (allowed ? "<green>Dozwolone</green>" : "<red>Zablokowane</red>") + "</gray>", "<yellow>Kliknij, aby przełączyć.</yellow>")));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do zarządzania członkiem.</gray>")));
        owner.openInventory(inv);
    }


    public void openMemberPermissionsAsAdmin(Player admin, Town town, UUID targetId, String targetName) {
        if (admin == null || town == null || !admin.hasPermission("hextowns.admin")) return;
        String safeName = safeName(targetId, targetName);
        Inventory inv = Bukkit.createInventory(new TownCoopDecisionMenuHolder(TownCoopDecisionMenuHolder.Action.MEMBER_PERMISSIONS, targetId, safeName, town.id(), true), 45,
                mini.deserialize("<dark_gray>DEV uprawnienia: " + safeName));
        fill(inv);
        var values = service.permissionsOf(targetId, town.id());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        TownPermission[] permissions = TownPermission.values();
        for (int i = 0; i < permissions.length && i < slots.length; i++) {
            TownPermission permission = permissions[i];
            boolean allowed = values.getOrDefault(permission, true);
            inv.setItem(slots[i], item(allowed ? Material.LIME_DYE : Material.GRAY_DYE,
                    (allowed ? "<green>" : "<red>") + permissionLabel(permission) + (allowed ? "</green>" : "</red>"),
                    List.of("<gray>Status: " + (allowed ? "<green>Dozwolone</green>" : "<red>Zablokowane</red>") + "</gray>",
                            "<light_purple>Zmiana administracyjna — zapisywana dla realnego członka.</light_purple>",
                            "<yellow>Kliknij, aby przełączyć.</yellow>")));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW, "<yellow>Powrót</yellow>", List.of("<gray>Wróć do zarządzania członkiem.</gray>")));
        admin.openInventory(inv);
    }

    private String permissionLabel(TownPermission permission) {
        return switch (permission) {
            case BUILD -> "Budowanie";
            case BREAK -> "Niszczenie bloków";
            case CONTAINERS -> "Skrzynie i magazyny";
            case MINION_USE -> "Obsługa minionów";
            case MINION_PICKUP -> "Podnoszenie minionów";
            case MACHINE_USE -> "Obsługa maszyn";
            case MACHINE_BREAK -> "Niszczenie maszyn";
            case BANK_WITHDRAW -> "Wypłaty z Banku Miasta";
        };
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
        if (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_PERMISSIONS && slot == BACK_SLOT) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (holder.adminOverride()) {
                    Town town = holder.townId() == null ? null : service.findTown(holder.townId()).orElse(null);
                    if (town != null) openMemberKickAsAdmin(player, town, holder.targetId(), holder.targetName());
                } else {
                    openMemberKick(player, holder.targetId(), holder.targetName());
                }
            });
            return;
        }
        if (slot == BACK_SLOT || (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_KICK && slot == ACCEPT_SLOT)) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("towncoop"));
            return;
        }
        if (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_KICK && slot == PERMISSIONS_SLOT) {
            if (holder.adminOverride()) {
                Town town = holder.townId() == null ? null : service.findTown(holder.townId()).orElse(null);
                if (town != null) openMemberPermissionsAsAdmin(player, town, holder.targetId(), holder.targetName());
            } else {
                openMemberPermissions(player, holder.targetId(), holder.targetName());
            }
            return;
        }
        if (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_PERMISSIONS) {
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
            TownPermission[] permissions = TownPermission.values();
            for (int i = 0; i < permissions.length && i < slots.length; i++) {
                if (slot != slots[i]) continue;
                Town town = holder.adminOverride() && holder.townId() != null
                        ? service.findTown(holder.townId()).orElse(null)
                        : service.townIdOf(player.getUniqueId()).flatMap(service::findTown).orElse(null);
                if (town == null) return;
                if (!holder.adminOverride() && !service.isOwner(player.getUniqueId(), town.id())) return;
                if (holder.adminOverride() && !player.hasPermission("hextowns.admin")) return;
                TownPermission permission = permissions[i];
                boolean next = !service.permissionsOf(holder.targetId(), town.id()).getOrDefault(permission, true);
                CompletableFuture<Boolean> change = holder.adminOverride()
                        ? service.setPermissionAsAdmin(player.getUniqueId(), town.id(), holder.targetId(), permission, next)
                        : service.setPermission(player.getUniqueId(), town.id(), holder.targetId(), permission, next);
                change.whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null || !Boolean.TRUE.equals(ok)) {
                                api.ui().send(player, "towns.error.db", hex.core.api.ui.UiTokens.of("error", error == null ? "Nie udało się zmienić uprawnienia." : error.getMessage()));
                                return;
                            }
                            if (holder.adminOverride()) openMemberPermissionsAsAdmin(player, town, holder.targetId(), holder.targetName());
                            else openMemberPermissions(player, holder.targetId(), holder.targetName());
                        }));
                return;
            }
            return;
        }
        CompletableFuture<OperationResult> action = null;
        if (holder.action() == TownCoopDecisionMenuHolder.Action.REQUEST_DECISION) {
            if (slot == ACCEPT_SLOT) {
                api.ui().send(player, "towns.accept.processing");
                action = service.acceptCoopRequest(player, holder.targetId(), holder.targetName());
            }
            if (slot == REJECT_SLOT) action = service.rejectCoopRequest(player, holder.targetId(), holder.targetName());
        } else if (holder.action() == TownCoopDecisionMenuHolder.Action.MEMBER_KICK && slot == REJECT_SLOT) {
            if (holder.adminOverride()) {
                player.sendMessage("§dTryb DEV właściciela nie wykonuje kicka członka — operacja destrukcyjna pozostaje zablokowana.");
                return;
            }
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
        if (meta != null) meta.setHideTooltip(true);
    }


    private String rankLore(UUID targetId) {
        String rank = service.playerRankDisplay(targetId);
        if (rank == null) return "<gray>Ranga: <gray>Gracz</gray></gray>";
        if (rank.contains("Media")) return "<gray>Ranga: <light_purple>Media</light_purple></gray>";
        if (rank.contains("Elita")) return "<gray>Ranga: <red>Elita</red></gray>";
        if (rank.contains("SVIP")) return "<gray>Ranga: <gold>SVIP</gold></gray>";
        if (rank.contains("VIP")) return "<gray>Ranga: <yellow>VIP</yellow></gray>";
        return "<gray>Ranga: <gray>Gracz</gray></gray>";
    }

    private String safeName(UUID targetId, String targetName) {
        return targetName == null || targetName.isBlank() ? targetId.toString().substring(0, 8) : targetName;
    }
}
