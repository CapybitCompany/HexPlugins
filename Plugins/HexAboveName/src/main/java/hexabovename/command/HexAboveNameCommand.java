package hexabovename.command;

import hexabovename.HexAboveNamePlugin;
import hexabovename.service.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class HexAboveNameCommand implements CommandExecutor, TabCompleter {

    private final HexAboveNamePlugin plugin;
    private final MessageService messageService;

    public HexAboveNameCommand(HexAboveNamePlugin plugin, MessageService messageService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("set")) {
            handleSet(sender, label, args);
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            handleClear(sender, args[1]);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            boolean success = plugin.reloadPluginRuntime();
            if (success) {
                messageService.sendReloaded(sender);
            } else {
                messageService.sendReloadFailed(sender);
            }
            return true;
        }

        messageService.sendUsage(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload", "set", "clear");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("clear"))) {
            List<String> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player.getName());
            }
            return players;
        }
        return Collections.emptyList();
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("hexabovename.admin") || sender.hasPermission("hexabovename.reload");
    }

    private void handleSet(CommandSender sender, String label, String[] args) {
        String targetInput = args[1];
        TargetPlayer target = resolveTarget(targetInput);
        if (target == null) {
            messageService.sendPlayerNotFound(sender, targetInput);
            return;
        }

        String title = joinFrom(args, 2).trim();
        if (title.isBlank()) {
            messageService.sendUsage(sender, label);
            return;
        }

        int maxLength = plugin.config().limits().maxTitleLength();
        if (title.length() > maxLength) {
            messageService.sendTitleTooLong(sender, maxLength);
            return;
        }

        plugin.mutationService()
                .setTitle(target.uuid(), target.name(), title)
                .whenComplete((unused, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Nie udało się ustawić tytułu dla " + target.name() + ": " + error.getMessage());
                        messageService.sendStorageWriteFailed(sender);
                        return;
                    }
                    if (plugin.cacheService() != null) {
                        plugin.cacheService().requestRefresh();
                    }
                    messageService.sendTitleSet(sender, target.name(), title);
                }));
    }

    private void handleClear(CommandSender sender, String playerName) {
        TargetPlayer target = resolveTarget(playerName);
        if (target == null) {
            messageService.sendPlayerNotFound(sender, playerName);
            return;
        }

        plugin.mutationService()
                .clearTitle(target.uuid(), target.name())
                .whenComplete((unused, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Nie udało się usunąć tytułu dla " + target.name() + ": " + error.getMessage());
                        messageService.sendStorageWriteFailed(sender);
                        return;
                    }
                    if (plugin.cacheService() != null) {
                        plugin.cacheService().requestRefresh();
                    }
                    if (plugin.renderService() != null) {
                        plugin.renderService().removeDisplayFor(target.uuid());
                    }
                    messageService.sendTitleCleared(sender, target.name());
                }));
    }

    private TargetPlayer resolveTarget(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new TargetPlayer(online.getUniqueId(), online.getName());
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && cached.getName() != null && !cached.getName().isBlank()) {
            return new TargetPlayer(cached.getUniqueId(), cached.getName());
        }

        return null;
    }

    private String joinFrom(String[] args, int startIndex) {
        StringBuilder builder = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private record TargetPlayer(
            UUID uuid,
            String name
    ) {
    }
}
