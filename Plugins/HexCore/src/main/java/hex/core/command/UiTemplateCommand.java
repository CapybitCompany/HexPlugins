package hex.core.command;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UiTemplateCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "§eUżycie: /uitpl <all|gracz> <template> [args...]";

    private final HexApi api;

    public UiTemplateCommand(HexApi api) {
        this.api = api;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hexcore.ui.send")) {
            sender.sendMessage("§cBrak uprawnień: hexcore.ui.send");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(USAGE);
            return true;
        }

        String targetArg = args[0];
        String templateKey = args[1];
        String[] values = Arrays.copyOfRange(args, 2, args.length);

        if (!api.ui().templates().contains(templateKey)) {
            sender.sendMessage("§cNie znaleziono template: §f" + templateKey);
            return true;
        }

        List<String> expected = api.ui().expectedArgs(templateKey);
        if (values.length < expected.size()) {
            sender.sendMessage("§cTemplate wymaga argumentów: §f" + String.join(", ", expected));
            sender.sendMessage(USAGE);
            return true;
        }
        if (expected.isEmpty() && values.length > 0) {
            sender.sendMessage("§cTemplate §f" + templateKey + " §cnie przyjmuje argumentów.");
            return true;
        }

        if (targetArg.equalsIgnoreCase("all")) {
            api.ui().broadcast(templateKey, values);
            sender.sendMessage("§aWysłano template do wszystkich graczy.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(targetArg);
        if (target == null) {
            sender.sendMessage("§cNie znaleziono gracza: §f" + targetArg);
            return true;
        }

        api.ui().send(target, templateKey, values);
        sender.sendMessage("§aWysłano template do §f" + target.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            out.add("all");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            return out;
        }

        if (args.length == 2) {
            return api.ui().templates().stream().sorted().toList();
        }

        if (args.length >= 3) {
            String templateKey = args[1];
            List<String> expected = api.ui().expectedArgs(templateKey);
            int index = args.length - 3;
            if (index >= 0 && index < expected.size()) {
                return List.of("<" + expected.get(index) + ">");
            }
        }

        return List.of();
    }
}

