package hexcustomitems.command;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

public final class HexCustomItemsCommand implements CommandExecutor, TabCompleter {

    private static final String SUBCOMMAND_GIVE = "give";
    private static final String SUBCOMMAND_RELOAD = "reload";
    private static final String SUBCOMMAND_LIST = "list";

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;
    private final GiveService giveService;
    private final MessageService messageService;
    private final Runnable reloadAction;

    public HexCustomItemsCommand(
            Supplier<HexCustomItemsConfig> configSupplier,
            CustomItemRegistryService registryService,
            GiveService giveService,
            MessageService messageService,
            Runnable reloadAction
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.giveService = Objects.requireNonNull(giveService, "giveService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        HexCustomItemsConfig config = configSupplier.get();
        if (args.length == 0) {
            messageService.sendUsageMain(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case SUBCOMMAND_GIVE -> {
                if (!sender.hasPermission(config.givePermission())) {
                    messageService.sendNoPermission(sender);
                    return true;
                }
                handleGive(sender, args);
                return true;
            }
            case SUBCOMMAND_RELOAD -> {
                if (!sender.hasPermission(config.reloadPermission())) {
                    messageService.sendNoPermission(sender);
                    return true;
                }
                reloadAction.run();
                messageService.sendReloaded(sender);
                return true;
            }
            case SUBCOMMAND_LIST -> {
                if (!sender.hasPermission(config.givePermission())) {
                    messageService.sendNoPermission(sender);
                    return true;
                }
                StringJoiner joiner = new StringJoiner(", ");
                for (String itemId : registryService.allItems().keySet()) {
                    joiner.add(itemId);
                }
                messageService.sendList(sender, joiner.toString());
                return true;
            }
            default -> {
                messageService.sendUsageMain(sender);
                return true;
            }
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            messageService.sendUsageGive(sender);
            return;
        }

        String itemId = args[1];
        String targetName = args[2];
        int amount = 1;

        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                messageService.sendInvalidNumber(sender);
                return;
            }
        }

        giveService.give(sender, itemId, targetName, amount);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String subcommand : List.of(SUBCOMMAND_GIVE, SUBCOMMAND_RELOAD, SUBCOMMAND_LIST)) {
                if (subcommand.startsWith(input)) {
                    suggestions.add(subcommand);
                }
            }
            return suggestions;
        }

        if (args.length == 2 && SUBCOMMAND_GIVE.equalsIgnoreCase(args[0])) {
            String input = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String itemId : registryService.allItems().keySet()) {
                if (itemId.startsWith(input)) {
                    suggestions.add(itemId);
                }
            }
            return suggestions;
        }

        if (args.length == 4 && SUBCOMMAND_GIVE.equalsIgnoreCase(args[0])) {
            return List.of("1", "2", "3", "5", "10");
        }

        return List.of();
    }
}
