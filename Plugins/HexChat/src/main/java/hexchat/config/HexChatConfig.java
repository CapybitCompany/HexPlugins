package hexchat.config;

import java.util.List;
import java.util.Objects;

public record HexChatConfig(
        Chat chat,
        Cooldown cooldown,
        ContentFilter contentFilter,
        PlayerMute playerMute,
        AutoMessages autoMessages,
        CommandFilter commandFilter,
        TabCompleteFilter tabCompleteFilter,
        Help help,
        Messages messages
) {
    public HexChatConfig {
        chat = Objects.requireNonNull(chat, "chat");
        cooldown = Objects.requireNonNull(cooldown, "cooldown");
        contentFilter = Objects.requireNonNull(contentFilter, "contentFilter");
        playerMute = Objects.requireNonNull(playerMute, "playerMute");
        autoMessages = Objects.requireNonNull(autoMessages, "autoMessages");
        commandFilter = Objects.requireNonNull(commandFilter, "commandFilter");
        tabCompleteFilter = Objects.requireNonNull(tabCompleteFilter, "tabCompleteFilter");
        help = Objects.requireNonNull(help, "help");
        messages = Objects.requireNonNull(messages, "messages");
    }

    public record Chat(
            boolean enabled,
            String format,
            GlobalMute globalMute,
            ConflictGuard conflictGuard
    ) {
        public Chat {
            format = Objects.requireNonNull(format, "format");
            globalMute = Objects.requireNonNull(globalMute, "globalMute");
            conflictGuard = Objects.requireNonNull(conflictGuard, "conflictGuard");
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

    /**
     * Ochrona przed konfliktami z innymi pluginami zarządzającymi czatem.
     * HexChat nigdy nie modyfikuje podpisanej treści wiadomości (tylko anuluje lub
     * podmienia render/wyświetlanie), więc sam nie powoduje "Chat Verification Error".
     * Guard wykrywa i raportuje inne pluginy czatu, a opcjonalnie wymusza render HexChat.
     */
    public record ConflictGuard(
            boolean enabled,
            boolean warnOnConflict,
            boolean enforceFormat,
            List<String> knownChatPlugins
    ) {
        public ConflictGuard {
            knownChatPlugins = List.copyOf(Objects.requireNonNull(knownChatPlugins, "knownChatPlugins"));
        }
    }

    public record ContentFilter(
            boolean enabled,
            String bypassPermission,
            String censorMask,
            AntiAdvertising antiAdvertising,
            Blacklist blacklist,
            AntiSpam antiSpam
    ) {
        public ContentFilter {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
            censorMask = Objects.requireNonNull(censorMask, "censorMask");
            if (censorMask.isBlank()) {
                throw new IllegalArgumentException("censorMask cannot be blank");
            }
            antiAdvertising = Objects.requireNonNull(antiAdvertising, "antiAdvertising");
            blacklist = Objects.requireNonNull(blacklist, "blacklist");
            antiSpam = Objects.requireNonNull(antiSpam, "antiSpam");
        }
    }

    public enum FilterAction {
        BLOCK,
        CENSOR
    }

    public record AntiAdvertising(
            boolean enabled,
            FilterAction action,
            String blockMessage,
            List<String> allowedDomains,
            List<String> extraPatterns
    ) {
        public AntiAdvertising {
            action = Objects.requireNonNull(action, "action");
            blockMessage = Objects.requireNonNull(blockMessage, "blockMessage");
            allowedDomains = List.copyOf(Objects.requireNonNull(allowedDomains, "allowedDomains"));
            extraPatterns = List.copyOf(Objects.requireNonNull(extraPatterns, "extraPatterns"));
        }
    }

    public record Blacklist(
            boolean enabled,
            FilterAction action,
            String blockMessage,
            boolean matchLeetspeak,
            List<String> words
    ) {
        public Blacklist {
            action = Objects.requireNonNull(action, "action");
            blockMessage = Objects.requireNonNull(blockMessage, "blockMessage");
            words = List.copyOf(Objects.requireNonNull(words, "words"));
        }
    }

    public record AntiSpam(
            boolean enabled,
            String blockMessage,
            int maxRepeatedMessages,
            int maxCapsPercentage,
            int minLengthForCapsCheck
    ) {
        public AntiSpam {
            blockMessage = Objects.requireNonNull(blockMessage, "blockMessage");
            maxRepeatedMessages = Math.max(2, maxRepeatedMessages);
            maxCapsPercentage = Math.min(100, Math.max(0, maxCapsPercentage));
            minLengthForCapsCheck = Math.max(1, minLengthForCapsCheck);
        }
    }

    public record PlayerMute(
            boolean enabled,
            String bypassPermission,
            String defaultReason
    ) {
        public PlayerMute {
            bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
            if (bypassPermission.isBlank()) {
                throw new IllegalArgumentException("bypassPermission cannot be blank");
            }
            defaultReason = Objects.requireNonNull(defaultReason, "defaultReason");
            if (defaultReason.isBlank()) {
                throw new IllegalArgumentException("defaultReason cannot be blank");
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
            String chatMuteStatusDisabled,
            String privateMuted,
            String playerMuteSet,
            String playerMuteRemoved,
            String playerMuteNotMuted,
            String playerMuteTargetNotFound,
            String playerMuteInfo,
            String playerMuteDurationInvalid
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
            privateMuted = Objects.requireNonNull(privateMuted, "privateMuted");
            playerMuteSet = Objects.requireNonNull(playerMuteSet, "playerMuteSet");
            playerMuteRemoved = Objects.requireNonNull(playerMuteRemoved, "playerMuteRemoved");
            playerMuteNotMuted = Objects.requireNonNull(playerMuteNotMuted, "playerMuteNotMuted");
            playerMuteTargetNotFound = Objects.requireNonNull(playerMuteTargetNotFound, "playerMuteTargetNotFound");
            playerMuteInfo = Objects.requireNonNull(playerMuteInfo, "playerMuteInfo");
            playerMuteDurationInvalid = Objects.requireNonNull(playerMuteDurationInvalid, "playerMuteDurationInvalid");
        }
    }
}
