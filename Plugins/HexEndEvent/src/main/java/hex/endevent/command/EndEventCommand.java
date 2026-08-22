package hex.endevent.command;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.endevent.HexEndEventPlugin;
import hex.endevent.service.EndEventService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class EndEventCommand implements CommandExecutor, TabCompleter {
    private final HexEndEventPlugin plugin;
    private final HexApi hex;
    private final EndEventService service;

    public EndEventCommand(HexEndEventPlugin plugin, HexApi hex, EndEventService service) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            service.sendStatus(sender);
            return true;
        }
        if (!args[0].equalsIgnoreCase("admin")) {
            service.sendStatus(sender);
            return true;
        }
        if (!sender.hasPermission("hexendevent.admin")) {
            hex.ui().send(sender, "endevent.error.no-permission");
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sendAdminStatus(sender);
            return true;
        }
        if (args[1].equalsIgnoreCase("reload")) {
            HexEndEventPlugin.ReloadResult result = plugin.reloadEndEventConfig();
            hex.ui().send(sender, result.success() ? "endevent.admin.reload.success" : "endevent.admin.reload.error",
                    UiTokens.of("error", result.message()));
            return true;
        }
        sendAdminStatus(sender);
        return true;
    }

    private void sendAdminStatus(CommandSender sender) {
        var runtime = service.runtime();
        UiTokens tokens = UiTokens.of("state", service.state().name())
                .put("enabled", String.valueOf(service.config().enabled()))
                .put("timezone", service.config().zoneId().toString())
                .put("next", service.nextOpenPlaceholder())
                .put("prepared", blank(runtime.preparedEventId()))
                .put("active", blank(runtime.activeEventId()))
                .put("reset_required", String.valueOf(runtime.resetRequired()))
                .put("loaded", String.valueOf(service.managedEndLoaded()))
                .put("players", String.valueOf(service.playersInEnd()));
        hex.ui().send(sender, "endevent.admin.status", tokens);
    }

    private static String blank(String value) { return value == null || value.isBlank() ? "-" : value; }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("hexendevent.admin")) return List.of();
        if (args.length == 1) return filter(List.of("admin"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return filter(List.of("status", "reload"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String raw) {
        String prefix = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
