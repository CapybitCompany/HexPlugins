package hexcustomitems.command;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.function.Supplier;

public final class LegacyGiveCommand implements CommandExecutor {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final GiveService giveService;
    private final MessageService messageService;
    private final String itemId;

    public LegacyGiveCommand(
            Supplier<HexCustomItemsConfig> configSupplier,
            GiveService giveService,
            MessageService messageService,
            String itemId
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.giveService = Objects.requireNonNull(giveService, "giveService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(configSupplier.get().givePermission())) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            messageService.sendUsageGive(sender);
            return true;
        }

        String target = args[0];
        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                messageService.sendInvalidNumber(sender);
                return true;
            }
        }

        giveService.give(sender, itemId, target, amount);
        return true;
    }
}
