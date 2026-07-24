package hexcustomitems.service;

import hexcustomitems.model.CustomItemDefinition;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;

/**
 * Steuert den Ablauf beim Rechtsklick auf ein Custom-Item:
 * Permission -&gt; Guard (offensiv) -&gt; Cooldown -&gt; Aktionen -&gt; Verbrauch -&gt; Cooldown setzen.
 */
public final class CustomItemUseService {

    private final CustomItemRegistryService registryService;
    private final CooldownService cooldownService;
    private final UsePolicyService policyService;
    private final ActionExecutor actionExecutor;
    private final MessageService messageService;

    public CustomItemUseService(
            CustomItemRegistryService registryService,
            CooldownService cooldownService,
            UsePolicyService policyService,
            ActionExecutor actionExecutor,
            MessageService messageService
    ) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
        this.policyService = Objects.requireNonNull(policyService, "policyService");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    /**
     * Verarbeitet einen Rechtsklick mit einem Custom-Item.
     *
     * <p>Gibt {@code true} nur zurück, wenn ein aktuell gültiges, nutzbares Custom-Item
     * verarbeitet wurde (auch wenn durch Cooldown/Permission/Guard geblockt - dann soll der
     * Vanilla-Klick unterbunden werden). Bei {@code false} handelt es sich um kein verwaltetes,
     * nutzbares Item (kein PDC, stale/entfernte ID oder Item ohne gültige Aktionen); der Aufrufer
     * darf den Rechtsklick dann nicht abbrechen.
     */
    public boolean tryUseItem(Player player, EquipmentSlot hand, ItemStack item) {
        String itemId = registryService.resolveItemId(item);
        if (itemId == null) {
            return false;
        }

        CustomItemDefinition definition = registryService.findById(itemId);
        if (definition == null) {
            // Stale PDC: ID zeigt auf ein Item, das (nach Reload/Config-Änderung) nicht mehr existiert.
            return false;
        }

        // Item ohne (gültige) Aktionen: nicht nutzbar - nicht verbrauchen, keinen Cooldown setzen,
        // Rechtsklick nicht blockieren. Der ConfigLoader überspringt solche Items bereits.
        if (definition.actions().isEmpty()) {
            return false;
        }

        if (definition.hasPermission() && !player.hasPermission(definition.permission())) {
            messageService.sendUseNoPermission(player);
            return true;
        }

        if (definition.hasOffensiveAction() && !policyService.allowsOffensive(player)) {
            messageService.sendRegionBlocked(player);
            return true;
        }

        long remaining = cooldownService.remainingSeconds(player.getUniqueId(), itemId);
        if (remaining > 0) {
            messageService.sendCooldownActive(player, remaining);
            return true;
        }

        actionExecutor.execute(player, definition, 1);
        consume(player, hand, definition);
        cooldownService.apply(player.getUniqueId(), itemId, definition.cooldownSeconds());
        return true;
    }

    /** Verbraucht das Item: eine Ladung (bei Ladungs-Items) oder ein Stück aus dem Stack. */
    private void consume(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        ItemStack current = player.getInventory().getItem(hand);
        if (current == null || current.getType().isAir()) {
            return;
        }

        if (definition.usesCharges()) {
            consumeCharge(player, hand, current, definition);
        } else {
            consumeOne(player, hand, current);
        }
    }

    private void consumeCharge(Player player, EquipmentSlot hand, ItemStack current, CustomItemDefinition definition) {
        int stored = registryService.readCharges(current);
        int remaining = (stored < 0 ? definition.charges() : stored) - 1;

        if (current.getAmount() > 1) {
            // Nur ein Stück des Stacks nutzt eine Ladung: abspalten.
            current.setAmount(current.getAmount() - 1);
            player.getInventory().setItem(hand, current);
            if (remaining > 0) {
                ItemStack single = registryService.createItem(definition, 1, player);
                registryService.writeCharges(single, definition, remaining, player);
                giveOrDrop(player, single);
            }
            return;
        }

        if (remaining > 0) {
            registryService.writeCharges(current, definition, remaining, player);
            player.getInventory().setItem(hand, current);
        } else {
            player.getInventory().setItem(hand, null);
        }
    }

    private void consumeOne(Player player, EquipmentSlot hand, ItemStack current) {
        int amount = current.getAmount();
        if (amount <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            current.setAmount(amount - 1);
            player.getInventory().setItem(hand, current);
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
