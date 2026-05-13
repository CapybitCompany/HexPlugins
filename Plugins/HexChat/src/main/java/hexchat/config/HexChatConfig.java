package hexchat.config;

import java.util.List;
import java.util.Objects;

public record HexChatConfig(
        Chat chat,
        Cooldown cooldown,
        AutoMessages autoMessages,
        CommandFilter commandFilter,
        TabCompleteFilter tabCompleteFilter,
        Help help,
        Messages messages
) {
    public HexChatConfig {
        chat = Objects.requireNonNull(chat, "chat");
        cooldown = Objects.requireNonNull(cooldown, "cooldown");
        autoMessages = Objects.requireNonNull(autoMessages, "autoMessages");
        commandFilter = Objects.requireNonNull(commandFilter, "commandFilter");
        tabCompleteFilter = Objects.requireNonNull(tabCompleteFilter, "tabCompleteFilter");
        help = Objects.requireNonNull(help, "help");
        messages = Objects.requireNonNull(messages, "messages");
    }

    public record Chat(
            boolean enabled,
            String format,
            GlobalMute globalMute
    ) {
        public Chat {
            format = Objects.requireNonNull(format, "format");
            globalMute = Objects.requireNonNull(globalMute, "globalMute");
            if (format.isBlank()) {
                throw new IllegalArgumentException("format cannot be blank");
            }
        }
    }

    public record GlobalMute(
            boolean enabled,
            boolean initiallyMuted,
            String bypassPermission
    ) {
        public GlobalMute {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
        }
    }

    public record Cooldown(
            boolean enabled,
            String bypassPermission,
            boolean useLuckPermsPrimaryGroup,
            int defaultSeconds,
            List<GroupCooldown> rankCooldowns,
            List<PermissionCooldown> permissionOverrides
    ) {
        public Cooldown {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
            defaultSeconds = Math.max(0, defaultSeconds);
            rankCooldowns = List.copyOf(Objects.requireNonNull(rankCooldowns, "rankCooldowns"));
            permissionOverrides = List.copyOf(Objects.requireNonNull(permissionOverrides, "permissionOverrides"));
        }
    }

    public record GroupCooldown(
            String rank,
            int seconds
    ) {
        public GroupCooldown {
            rank = Objects.requireNonNull(rank, "rank");
            if (rank.isBlank()) {
                throw new IllegalArgumentException("rank cannot be blank");
            }
            seconds = Math.max(0, seconds);
        }
    }

    public record PermissionCooldown(
            String permission,
            int seconds
    ) {
        public PermissionCooldown {
            permission = Objects.requireNonNull(permission, "permission");
            if (permission.isBlank()) {
                throw new IllegalArgumentException("permission cannot be blank");
            }
            seconds = Math.max(0, seconds);
        }
    }

    public record AutoMessages(
            boolean enabled,
            int intervalSeconds,
            boolean randomOrder,
            List<String> messages
    ) {
        public AutoMessages {
            intervalSeconds = Math.max(1, intervalSeconds);
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    public record CommandFilter(
            boolean enabled,
            String bypassPermission,
            List<String> allowedCommands,
            String blockedMessage,
            boolean hideNamespacedSuggestions,
            List<String> allowedNamespacedSuggestions
    ) {
        public CommandFilter {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
            allowedCommands = List.copyOf(Objects.requireNonNull(allowedCommands, "allowedCommands"));
            blockedMessage = Objects.requireNonNull(blockedMessage, "blockedMessage");
            if (blockedMessage.isBlank()) {
                throw new IllegalArgumentException("blockedMessage cannot be blank");
            }
            allowedNamespacedSuggestions = List.copyOf(Objects.requireNonNull(allowedNamespacedSuggestions, "allowedNamespacedSuggestions"));
        }
    }

    public record TabCompleteFilter(
            boolean enabled,
            String bypassPermission,
            List<String> hiddenCommands
    ) {
        public TabCompleteFilter {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
            hiddenCommands = List.copyOf(Objects.requireNonNull(hiddenCommands, "hiddenCommands"));
        }
    }

    public record Help(
            boolean enabled,
            Mode mode,
            List<String> commandAliases,
            List<String> customLines,
            boolean fallbackToCustomWhenEssentialsMissing,
            String unavailableMessage
    ) {
        public Help {
            mode = Objects.requireNonNull(mode, "mode");
            commandAliases = List.copyOf(Objects.requireNonNull(commandAliases, "commandAliases"));
            customLines = List.copyOf(Objects.requireNonNull(customLines, "customLines"));
            unavailableMessage = Objects.requireNonNull(unavailableMessage, "unavailableMessage");
            if (unavailableMessage.isBlank()) {
                throw new IllegalArgumentException("unavailableMessage cannot be blank");
            }
        }

        public enum Mode {
            CUSTOM,
            ESSENTIALS
        }
    }

    public record Messages(
            String prefix,
            String noPermission,
            String reloaded,
            String usage,
            String cooldownWait,
            String chatMuted,
            String chatMuteEnabled,
            String chatMuteDisabled,
            String chatMuteAlreadyEnabled,
            String chatMuteAlreadyDisabled,
            String chatMuteStatusEnabled,
            String chatMuteStatusDisabled
    ) {
        public Messages {
            prefix = Objects.requireNonNull(prefix, "prefix");
            noPermission = Objects.requireNonNull(noPermission, "noPermission");
            reloaded = Objects.requireNonNull(reloaded, "reloaded");
            usage = Objects.requireNonNull(usage, "usage");
            cooldownWait = Objects.requireNonNull(cooldownWait, "cooldownWait");
            chatMuted = Objects.requireNonNull(chatMuted, "chatMuted");
            chatMuteEnabled = Objects.requireNonNull(chatMuteEnabled, "chatMuteEnabled");
            chatMuteDisabled = Objects.requireNonNull(chatMuteDisabled, "chatMuteDisabled");
            chatMuteAlreadyEnabled = Objects.requireNonNull(chatMuteAlreadyEnabled, "chatMuteAlreadyEnabled");
            chatMuteAlreadyDisabled = Objects.requireNonNull(chatMuteAlreadyDisabled, "chatMuteAlreadyDisabled");
            chatMuteStatusEnabled = Objects.requireNonNull(chatMuteStatusEnabled, "chatMuteStatusEnabled");
            chatMuteStatusDisabled = Objects.requireNonNull(chatMuteStatusDisabled, "chatMuteStatusDisabled");
        }
    }
}
