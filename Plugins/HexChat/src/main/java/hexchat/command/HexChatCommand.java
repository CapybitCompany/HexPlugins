package hexchat.command;

import hexchat.HexChatPlugin;
import hexchat.config.HexChatConfig;
import hexchat.mute.MuteEntry;
import hexchat.permission.HexChatPermissions;
import hexchat.service.HexChatMessageService;
import hexchat.service.PlayerDirectory;
import hexchat.service.PlayerMuteService;
import hexchat.util.DurationUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class HexChatCommand implements CommandExecutor, TabCompleter {

    private static final String RELOAD_SUBCOMMAND = "reload";
    private static final String MUTE_SUBCOMMAND = "mute";
    private static final String UNMUTE_SUBCOMMAND = "unmute";
    private static final String TOGGLE_MUTE_SUBCOMMAND = "togglemute";
    private static final String MUTE_STATUS_SUBCOMMAND = "mutestatus";
    private static final String MUTE_INFO_SUBCOMMAND = "muteinfo";
    private static final List<String> SUBCOMMANDS = List.of(
            RELOAD_SUBCOMMAND,
            MUTE_SUBCOMMAND,
            UNMUTE_SUBCOMMAND,
            TOGGLE_MUTE_SUBCOMMAND,
            MUTE_STATUS_SUBCOMMAND,
            MUTE_INFO_SUBCOMMAND
    );

    private final HexChatPlugin plugin;
    private final HexChatMessageService messages;
    private final PlayerMuteService playerMuteService;
    private final PlayerDirectory playerDirectory;
    private final Supplier<HexChatConfig> configSupplier;

    public HexChatCommand(
            HexChatPlugin plugin,
            HexChatMessageService messages,
            PlayerMuteService playerMuteService,
            PlayerDirectory playerDirectory,
            Supplier<HexChatConfig> configSupplier
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.playerMuteService = Objects.requireNonNull(playerMuteService, "playerMuteService");
        this.playerDirectory = Objects.requireNonNull(playerDirectory, "playerDirectory");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(HexChatPermissions.ADMIN)) {
            messages.sendNoPermission(sender);
            return true;
        }

        if (args.length == 0) {
            messages.sendUsage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case RELOAD_SUBCOMMAND -> {
                if (args.length != 1) {
                    messages.sendUsage(sender);
                    return true;
                }
                plugin.reloadHexChatConfiguration();
                messages.sendReloaded(sender);
            }
            case MUTE_SUBCOMMAND -> handleMute(sender, args);
            case UNMUTE_SUBCOMMAND -> handleUnmute(sender, args);
            case TOGGLE_MUTE_SUBCOMMAND -> {
                if (args.length != 1) {
                    messages.sendUsage(sender);
                    return true;
                }
                handleToggleGlobalMute(sender);
            }
            case MUTE_STATUS_SUBCOMMAND -> {
                if (args.length != 1) {
                    messages.sendUsage(sender);
                    return true;
                }
                messages.sendChatMuteStatus(sender, plugin.isGlobalChatMuted());
            }
            case MUTE_INFO_SUBCOMMAND -> handleMuteInfo(sender, args);
            default -> messages.sendUsage(sender);
        }

        return true;
    }

    private static final List<String> PLAYER_TARGET_SUBCOMMANDS = List.of(
            MUTE_SUBCOMMAND, UNMUTE_SUBCOMMAND, MUTE_INFO_SUBCOMMAND
    );
    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "1h", "2h", "1d", "7d", "perm");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(HexChatPermissions.ADMIN)) {
            return List.of();
        }

        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        // /hexchat <mute|unmute|muteinfo> <TAB> -> nazwy graczy online
        if (args.length == 2 && PLAYER_TARGET_SUBCOMMANDS.contains(subcommand)) {
            return filterByPrefix(playerDirectory.onlineNames(args[1]), args[1]);
        }

        // /hexchat mute <gracz> <TAB> -> propozycje czasu trwania
        if (args.length == 3 && subcommand.equals(MUTE_SUBCOMMAND)) {
            return filterByPrefix(DURATION_SUGGESTIONS, args[2]);
        }

        // Po dacie następuje wolny tekst (powód) -> brak podpowiedzi.
        return List.of();
    }

    private static List<String> filterByPrefix(List<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private void handleMute(CommandSender sender, String[] args) {
        // Bez argumentu gracza -> globalne wyciszenie (zachowanie zgodne wstecznie).
        if (args.length == 1) {
            handleGlobalMute(sender, true);
            return;
        }

        String targetName = args[1];
        Optional<PlayerDirectory.ResolvedPlayer> resolved = playerDirectory.resolve(targetName);
        if (resolved.isEmpty()) {
            messages.sendPlayerMuteTargetNotFound(sender, targetName);
            return;
        }

        long durationMillis = DurationUtil.PERMANENT;
        int reasonStart = 2;
        if (args.length >= 3) {
            String durationToken = args[2];
            Optional<Long> parsed = DurationUtil.parseMillis(durationToken);
            if (parsed.isPresent()) {
                durationMillis = parsed.get();
                reasonStart = 3;
            } else if (looksLikeDuration(durationToken)) {
                messages.sendPlayerMuteDurationInvalid(sender, durationToken);
                return;
            }
        }

        String reason = joinFrom(args, reasonStart);
        if (reason.isBlank()) {
            reason = configSupplier.get().playerMute().defaultReason();
        }

        PlayerDirectory.ResolvedPlayer target = resolved.get();
        MuteEntry entry = playerMuteService.mute(target.uuid(), target.name(), durationMillis, reason);
        String timeText = entry.permanent()
                ? configSupplier.get().messages().muteTimePermanent()
                : DurationUtil.formatRemaining(playerMuteService.remainingMillis(entry));

        String finalReason = reason;
        messages.sendPlayerMuteSet(sender, target.name(), timeText, finalReason);
        // Gracz online dostaje osobny, konfigurowalny tekst powiadomienia o nałożonym wyciszeniu.
        playerDirectory.notifyIfOnline(
                target.uuid(),
                player -> messages.sendPlayerMuteNotification(player, target.name(), timeText, finalReason)
        );
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (args.length == 1) {
            handleGlobalMute(sender, false);
            return;
        }

        String targetName = args[1];
        Optional<PlayerDirectory.ResolvedPlayer> resolved = playerDirectory.resolve(targetName);
        if (resolved.isEmpty()) {
            messages.sendPlayerMuteTargetNotFound(sender, targetName);
            return;
        }

        PlayerDirectory.ResolvedPlayer target = resolved.get();
        if (playerMuteService.unmute(target.uuid())) {
            messages.sendPlayerMuteRemoved(sender, target.name());
        } else {
            messages.sendPlayerMuteNotMuted(sender, target.name());
        }
    }

    private void handleMuteInfo(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.sendUsage(sender);
            return;
        }

        String targetName = args[1];
        Optional<PlayerDirectory.ResolvedPlayer> resolved = playerDirectory.resolve(targetName);
        if (resolved.isEmpty()) {
            messages.sendPlayerMuteTargetNotFound(sender, targetName);
            return;
        }

        PlayerDirectory.ResolvedPlayer target = resolved.get();
        Optional<MuteEntry> mute = playerMuteService.activeMute(target.uuid());
        if (mute.isEmpty()) {
            messages.sendPlayerMuteNotMuted(sender, target.name());
            return;
        }

        MuteEntry entry = mute.get();
        String timeText = entry.permanent()
                ? configSupplier.get().messages().muteTimePermanent()
                : DurationUtil.formatRemaining(playerMuteService.remainingMillis(entry));
        String reason = entry.reason().isBlank()
                ? configSupplier.get().playerMute().defaultReason()
                : entry.reason();
        messages.sendPlayerMuteInfo(sender, target.name(), timeText, reason);
    }

    private void handleGlobalMute(CommandSender sender, boolean targetState) {
        boolean previous = plugin.setGlobalChatMuted(targetState);
        if (targetState) {
            if (previous) {
                messages.sendChatMuteAlreadyEnabled(sender);
                return;
            }
            messages.sendChatMuteEnabled(sender);
            return;
        }

        if (!previous) {
            messages.sendChatMuteAlreadyDisabled(sender);
            return;
        }
        messages.sendChatMuteDisabled(sender);
    }

    private void handleToggleGlobalMute(CommandSender sender) {
        boolean mutedNow = plugin.toggleGlobalChatMuted();
        if (mutedNow) {
            messages.sendChatMuteEnabled(sender);
            return;
        }
        messages.sendChatMuteDisabled(sender);
    }

    private static boolean looksLikeDuration(String token) {
        return !token.isEmpty() && Character.isDigit(token.charAt(0));
    }

    private static String joinFrom(String[] args, int start) {
        if (start >= args.length) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
