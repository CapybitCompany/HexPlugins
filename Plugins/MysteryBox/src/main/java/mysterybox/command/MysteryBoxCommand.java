package mysterybox.command;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.service.ItemFactoryService;
import mysterybox.service.MessageService;
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

public final class MysteryBoxCommand implements CommandExecutor, TabCompleter {

    private static final String RELOAD_SUBCOMMAND = "reload";

    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ItemFactoryService itemFactoryService;
    private final MessageService messageService;
    private final Runnable reloadAction;

    public MysteryBoxCommand(
            Supplier<MysteryBoxConfig> configSupplier,
            ItemFactoryService itemFactoryService,
            MessageService messageService,
            Runnable reloadAction
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MysteryBoxConfig config = configSupplier.get();
        if (!sender.hasPermission(config.commands().givePermission())) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (args.length == 1 && RELOAD_SUBCOMMAND.equalsIgnoreCase(args[0])) {
            reloadAction.run();
            messageService.sendReloaded(sender);
            return true;
        }

        if (args.length > 2) {
            messageService.sendUsageMysteryBox(sender);
            return true;
        }

        Player target = null;
        int amount = 1;

        if (args.length == 0) {
            if (sender instanceof Player player) {
                target = player;
            } else {
                messageService.sendUsageMysteryBox(sender);
                return true;
            }
        } else {
            if (isInteger(args[0])) {
                amount = parsePositiveInt(args[0], 1);
                if (sender instanceof Player player) {
                    target = player;
                } else {
                    messageService.sendUsageMysteryBox(sender);
                    return true;
                }
            } else {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    target = Bukkit.getPlayer(args[0]);
                }
            }

            if (args.length == 2) {
                amount = parsePositiveInt(args[1], 1);
            }
        }

        if (target == null) {
            messageService.sendPlayerNotFound(sender);
            return true;
        }

        int maxAmount = config.commands().maxGiveAmount();
        int finalAmount = Math.max(1, Math.min(maxAmount, amount));

        target.getInventory().addItem(itemFactoryService.createMysteryBoxItem(finalAmount));

        messageService.sendGiveSuccessSender(sender, target.getName(), finalAmount);
        messageService.sendGiveSuccessTarget(target, finalAmount);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(configSupplier.get().commands().givePermission())) {
            return List.of();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            if (RELOAD_SUBCOMMAND.startsWith(input)) {
                suggestions.add(RELOAD_SUBCOMMAND);
            }
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String name = onlinePlayer.getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(input)) {
                    suggestions.add(name);
                }
            }
            return suggestions;
        }

        if (args.length == 2) {
            return List.of("1", "5", "10", "16", "32", "64");
        }

        return List.of();
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int parsePositiveInt(String rawValue, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(rawValue));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
