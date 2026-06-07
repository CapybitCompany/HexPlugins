package hex.elimination.command;

import hex.core.api.ui.UiTokens;
import hex.elimination.HexEliminationPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Główna komenda /hexelimination z podkomendami:
 *   resurect <nick>
 *   resurectall [gamemode]
 *   start
 *   stop
 *   reload
 */
public class HexEliminationCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("resurect", "resurectall", "start", "stop", "reload");

    private final HexEliminationPlugin plugin;

    public HexEliminationCommand(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        return switch (sub) {
            case "resurect"    -> handleResurect(sender, args);
            case "resurectall" -> handleResurectAll(sender, args);
            case "start"       -> handleToggle(sender, true);
            case "stop"        -> handleToggle(sender, false);
            case "reload"      -> handleReload(sender);
            default            -> { sendUsage(sender); yield true; }
        };
    }

    // -------------------------------------------------------------------------

    private boolean handleResurect(CommandSender sender, String[] args) {
        if (args.length != 2) {
            plugin.ui().send(sender, "elimination.resurect.usage");
            return true;
        }

        String nick = args[1];
        OfflinePlayer target = plugin.getEliminationService().findPlayerByName(nick);
        if (target == null) {
            plugin.ui().send(sender, "elimination.error.player_not_found",
                    UiTokens.of("nick", nick));
            return true;
        }

        boolean ok = plugin.getEliminationService().resurrect(target);
        if (!ok) {
            plugin.ui().send(sender, "elimination.error.not_eliminated");
            return true;
        }

        String targetName = target.getName() == null ? nick : target.getName();
        plugin.ui().broadcast("elimination.resurect.announce",
                UiTokens.of("target", targetName).put("by", sender.getName()));
        plugin.ui().send(sender, "elimination.resurect.ok",
                UiTokens.of("target", targetName));
        return true;
    }

    private boolean handleResurectAll(CommandSender sender, String[] args) {
        GameMode targetMode;

        if (args.length == 1) {
            targetMode = plugin.getEliminationService().getResurrectGamemode();
        } else if (args.length == 2) {
            try {
                targetMode = GameMode.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.ui().send(sender, "elimination.resurectall.invalid_gamemode",
                        UiTokens.of("input", args[1]));
                return true;
            }
        } else {
            plugin.ui().send(sender, "elimination.resurectall.usage");
            return true;
        }

        int count = plugin.getEliminationService().resurrectAll(targetMode);

        if (count == 0) {
            plugin.ui().send(sender, "elimination.resurectall.empty");
            return true;
        }

        plugin.ui().broadcast("elimination.resurectall.announce",
                UiTokens.of("count", String.valueOf(count))
                        .put("gamemode", targetMode.name().toLowerCase(Locale.ROOT))
                        .put("by", sender.getName()));
        plugin.ui().send(sender, "elimination.resurectall.ok",
                UiTokens.of("count", String.valueOf(count)));
        return true;
    }

    private boolean handleToggle(CommandSender sender, boolean start) {
        if (start) {
            if (plugin.getEliminationService().isActive()) {
                plugin.ui().send(sender, "elimination.toggle.already_started");
                return true;
            }
            plugin.getEliminationService().enable();
            plugin.ui().broadcast("elimination.toggle.started",
                    UiTokens.of("by", sender.getName()));
        } else {
            if (!plugin.getEliminationService().isActive()) {
                plugin.ui().send(sender, "elimination.toggle.already_stopped");
                return true;
            }
            plugin.getEliminationService().disable();
            plugin.ui().broadcast("elimination.toggle.stopped",
                    UiTokens.of("by", sender.getName()));
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getEliminationService().reloadConfig();
        plugin.ui().send(sender, "elimination.reload.ok");
        return true;
    }

    private void sendUsage(CommandSender sender) {
        plugin.ui().send(sender, "elimination.usage");
    }

    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(input))
                    .toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String input = args[1].toLowerCase(Locale.ROOT);

            return switch (sub) {
                case "resurect" -> {
                    List<String> names = new ArrayList<>();
                    for (var p : Bukkit.getOnlinePlayers()) {
                        if (plugin.getEliminationService().isEliminated(p.getUniqueId())
                                && p.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                            names.add(p.getName());
                        }
                    }
                    yield names;
                }
                case "resurectall" -> List.of("survival", "creative", "adventure", "spectator")
                        .stream().filter(m -> m.startsWith(input)).toList();
                default -> List.of();
            };
        }

        return List.of();
    }
}

