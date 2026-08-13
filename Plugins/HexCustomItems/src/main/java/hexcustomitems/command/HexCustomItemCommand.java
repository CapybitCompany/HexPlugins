package hexcustomitems.command;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import hexcustomitems.ui.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class HexCustomItemCommand implements CommandExecutor, TabCompleter {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;
    private final GiveService giveService;
    private final MenuService menuService;
    private final MessageService messages;
    private final Runnable reloadAction;

    public HexCustomItemCommand(
            Supplier<HexCustomItemsConfig> configSupplier,
            CustomItemRegistryService registryService,
            GiveService giveService,
            MenuService menuService,
            MessageService messages,
            Runnable reloadAction
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.giveService = Objects.requireNonNull(giveService, "giveService");
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        HexCustomItemsConfig config = configSupplier.get();
        if (args.length == 0) {
            messages.sendUsageMain(sender);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if ("reload".equals(first)) {
            if (!sender.hasPermission(config.reloadPermission())) {
                messages.sendNoPermission(sender);
                return true;
            }
            reloadAction.run();
            messages.sendReloaded(sender);
            return true;
        }

        if ("adminpanel".equals(first)) {
            if (!sender.hasPermission(config.givePermission())) {
                messages.sendNoPermission(sender);
                return true;
            }
            if (!(sender instanceof Player player)) {
                messages.sendUsageMain(sender);
                return true;
            }
            menuService.open(player, null, 0);
            return true;
        }

        if (!sender.hasPermission(config.givePermission())) {
            messages.sendNoPermission(sender);
            return true;
        }
        if (args.length != 3) {
            messages.sendUsageGive(sender);
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            messages.sendInvalidNumber(sender);
            return true;
        }
        giveService.give(sender, args[0], args[1], amount);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String option : List.of("reload", "adminpanel")) {
                if (option.startsWith(input)) {
                    suggestions.add(option);
                }
            }
            for (var item : registryService.allItems().values()) {
                if (item.id().startsWith(input) || item.key().startsWith(input)) {
                    suggestions.add(item.id());
                }
            }
            return suggestions;
        }
        if (args.length == 2 && !"reload".equalsIgnoreCase(args[0]) && !"adminpanel".equalsIgnoreCase(args[0])) {
            return onlinePlayerNames(args[1]);
        }
        if (args.length == 3) {
            return List.of("1", "8", "16", "64");
        }
        return List.of();
    }

    private List<String> onlinePlayerNames(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
