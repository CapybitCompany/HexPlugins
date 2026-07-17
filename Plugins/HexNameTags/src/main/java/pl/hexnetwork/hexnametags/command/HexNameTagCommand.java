package pl.hexnetwork.hexnametags.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pl.hexnetwork.hexnametags.NameTagManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class HexNameTagCommand implements CommandExecutor, TabCompleter {
    private static final String LINE_SEPARATOR = "|";

    private final NameTagManager manager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public HexNameTagCommand(NameTagManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "test" -> {
                Player target;
                if (args.length >= 2) {
                    target = resolveOnlinePlayer(sender, args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(Component.text("Użycie z konsoli: /" + label + " test <gracz>", NamedTextColor.YELLOW));
                    return true;
                }
                if (target == null) {
                    return true;
                }

                manager.setTemporaryPlayerTag(target, List.of(
                        Component.text("HEX NETWORK", NamedTextColor.GOLD),
                        Component.text("Packet TextDisplay", NamedTextColor.AQUA),
                        Component.text("bez ArmorStandów", NamedTextColor.GRAY)
                ));
                sender.sendMessage(Component.text("Ustawiono testowy tag nad graczem " + target.getName() + ". Ten test nie zapisuje się do DB.", NamedTextColor.GREEN));
                warnIfSelfTagMayBeHidden(sender, target);
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Użycie: /" + label + " set <gracz> <tekst MiniMessage>. Linie rozdziel przez |", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = resolveOnlinePlayer(sender, args[1]);
                if (target == null) {
                    return true;
                }

                List<Component> lines = parseLines(sender, args, 2);
                if (lines.isEmpty()) {
                    return true;
                }

                manager.setPlayerTag(target, lines);
                sender.sendMessage(Component.text("Ustawiono i zapisano tag gracza " + target.getName() + ".", NamedTextColor.GREEN));
                warnIfSelfTagMayBeHidden(sender, target);
                return true;
            }
            case "self" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Ta komenda jest tylko dla gracza. Z konsoli użyj: /" + label + " set <gracz> <tekst>", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Użycie: /" + label + " self <tekst MiniMessage>. Linie rozdziel przez |", NamedTextColor.YELLOW));
                    return true;
                }

                List<Component> lines = parseLines(sender, args, 1);
                if (lines.isEmpty()) {
                    return true;
                }

                manager.setPlayerTag(player, lines);
                sender.sendMessage(Component.text("Ustawiono i zapisano Twój tag.", NamedTextColor.GREEN));
                warnIfSelfTagMayBeHidden(sender, player);
                return true;
            }
            case "clear" -> {
                Player target;
                if (args.length >= 2) {
                    target = resolveOnlinePlayer(sender, args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(Component.text("Użycie z konsoli: /" + label + " clear <gracz>", NamedTextColor.YELLOW));
                    return true;
                }
                if (target == null) {
                    return true;
                }

                manager.clearTag(target);
                sender.sendMessage(Component.text("Usunięto tag gracza " + target.getName() + " z pamięci oraz z DB, jeśli integracja HexCore DB jest aktywna.", NamedTextColor.GREEN));
                return true;
            }
            case "show", "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Użycie: /" + label + " show <gracz>", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = resolveOnlinePlayer(sender, args[1]);
                if (target == null) {
                    return true;
                }
                Optional<List<Component>> optionalLines = manager.getTagLines(target);
                if (optionalLines.isEmpty() || optionalLines.get().isEmpty()) {
                    sender.sendMessage(Component.text("Gracz " + target.getName() + " nie ma aktywnego tagu w pamięci.", NamedTextColor.YELLOW));
                    return true;
                }
                sender.sendMessage(Component.text("Tag gracza " + target.getName() + ":", NamedTextColor.GOLD));
                List<Component> lines = optionalLines.get();
                for (int i = 0; i < lines.size(); i++) {
                    sender.sendMessage(Component.text((i + 1) + ". ", NamedTextColor.GRAY).append(lines.get(i)));
                }
                return true;
            }
            case "reload" -> {
                manager.reloadSettings();
                manager.start();
                manager.refreshNow();
                sender.sendMessage(Component.text("Przeładowano HexNameTags.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Nieznana subkomenda.", NamedTextColor.RED));
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("HexNameTags - komendy administracyjne:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " set <gracz> <Linia 1 | Linia 2 | Linia 3> - ustawia/zastępuje cały tag", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " self <Linia 1 | Linia 2 | Linia 3> - ustawia/zastępuje Twój tag", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " clear [gracz] - usuwa tag gracza", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " show <gracz> - pokazuje aktualne linie", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " test [gracz] - test runtime-only bez DB", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/" + label + " reload - przeładowuje config", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Kolory i formatowanie: MiniMessage, np. <gold>HEX | <aqua>Admin", NamedTextColor.GRAY));
    }

    private Player resolveOnlinePlayer(CommandSender sender, String input) {
        Player exact = Bukkit.getPlayerExact(input);
        if (exact != null) {
            return exact;
        }

        List<Player> matches = Bukkit.matchPlayer(input);
        if (matches.isEmpty()) {
            sender.sendMessage(Component.text("Nie znaleziono online gracza: " + input, NamedTextColor.RED));
            return null;
        }
        if (matches.size() > 1) {
            sender.sendMessage(Component.text("Znaleziono kilku graczy dla '" + input + "'. Wpisz pełny nick.", NamedTextColor.RED));
            return null;
        }
        return matches.get(0);
    }

    private List<Component> parseLines(CommandSender sender, String[] args, int startIndex) {
        String input = String.join(" ", Arrays.copyOfRange(args, startIndex, args.length));
        List<Component> lines = parseLines(input);
        if (lines.isEmpty()) {
            sender.sendMessage(Component.text("Tekst nie może być pusty. Linie rozdziel przez " + LINE_SEPARATOR, NamedTextColor.RED));
        }
        return lines;
    }

    private List<Component> parseLines(String input) {
        List<Component> lines = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return lines;
        }
        for (String part : input.split("\\|")) {
            if (!part.isBlank()) {
                lines.add(miniMessage.deserialize(part.trim()));
            }
        }
        return lines;
    }

    private void warnIfSelfTagMayBeHidden(CommandSender sender, Player target) {
        if (sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId()) && !manager.isShowOwnTag()) {
            sender.sendMessage(Component.text("Uwaga: show-own-tag=false, więc możesz nie widzieć własnego taga. Inni gracze nadal mogą go widzieć.", NamedTextColor.YELLOW));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("help", "test", "set", "self", "clear", "show", "info", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2 && List.of("test", "set", "clear", "show", "info").contains(sub)) {
            String prefix = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        return List.of();
    }
}
