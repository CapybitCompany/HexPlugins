package mysterybox.command;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.service.ItemFactoryService;
import mysterybox.service.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Supplier;

public final class MysteryBoxVipCommand implements CommandExecutor {

    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ItemFactoryService itemFactoryService;
    private final MessageService messageService;

    public MysteryBoxVipCommand(
            Supplier<MysteryBoxConfig> configSupplier,
            ItemFactoryService itemFactoryService,
            MessageService messageService
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MysteryBoxConfig config = configSupplier.get();
        if (!sender.hasPermission(config.commands().givePermission())) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            messageService.sendOnlyPlayer(sender);
            return true;
        }

        player.getInventory().addItem(itemFactoryService.createVipVoucherItem(1));
        messageService.sendVoucherGiveSuccess(player);
        return true;
    }
}
