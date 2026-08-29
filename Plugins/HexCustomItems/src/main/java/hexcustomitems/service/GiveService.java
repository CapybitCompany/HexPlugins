package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class GiveService {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;
    private final MessageService messageService;

    public GiveService(
            Supplier<HexCustomItemsConfig> configSupplier,
            CustomItemRegistryService registryService,
            MessageService messageService
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    public boolean give(CommandSender sender, String itemId, String playerName, int requestedAmount) {
        CustomItemDefinition item = registryService.findById(itemId);
        if (item == null) {
            messageService.sendItemNotFound(sender, itemId);
            return false;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            messageService.sendPlayerNotFound(sender);
            return false;
        }

        int cappedAmount = Math.min(configSupplier.get().maxGiveAmount(), Math.max(1, requestedAmount));
        giveTo(target, item, cappedAmount);

        Component itemName = TextUtil.itemName(item.name(), Map.of(), target);
        messageService.sendGivenSender(sender, String.valueOf(cappedAmount), itemName, target.getName());
        messageService.sendGivenTarget(target, String.valueOf(cappedAmount), itemName);
        return true;
    }

    /** Gibt dem Spieler das Item; Ladungs-Items werden als einzelne Stuecke uebergeben. */
    public void giveTo(Player target, CustomItemDefinition item, int amount) {
        if (item.usesCharges()) {
            for (int i = 0; i < amount; i++) {
                addOrDrop(target, registryService.createItem(item, 1, target));
            }
        } else {
            addOrDrop(target, registryService.createItem(item, amount, target));
        }
    }

    private void addOrDrop(Player target, ItemStack stack) {
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(stack);
        for (ItemStack leftover : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
    }
}
