package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
            target = Bukkit.getPlayer(playerName);
        }

        if (target == null) {
            messageService.sendPlayerNotFound(sender);
            return false;
        }

        int cappedAmount = Math.min(configSupplier.get().maxGiveAmount(), Math.max(1, requestedAmount));
        ItemStack stack = registryService.createItem(item, cappedAmount);
        target.getInventory().addItem(stack);

        messageService.sendGivenSender(sender, String.valueOf(cappedAmount), item.name(), target.getName());
        messageService.sendGivenTarget(target, String.valueOf(cappedAmount), item.name());
        return true;
    }
}
