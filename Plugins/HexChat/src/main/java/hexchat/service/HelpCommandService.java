package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.util.CommandNormalizationUtil;
import org.bukkit.plugin.PluginManager;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class HelpCommandService {

    private final PluginManager pluginManager;
    private final Logger logger;
    private final AtomicReference<State> stateRef;
    private final AtomicBoolean warnedAboutMissingEssentials = new AtomicBoolean(false);

    public HelpCommandService(PluginManager pluginManager, Logger logger, HexChatConfig initialConfig) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateRef = new AtomicReference<>(State.from(initialConfig.help()));
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        Objects.requireNonNull(updatedConfig, "updatedConfig");
        this.stateRef.set(State.from(updatedConfig.help()));
        this.warnedAboutMissingEssentials.set(false);
    }

    public boolean handleIfNeeded(org.bukkit.entity.Player player, String rawCommand, HexChatMessageService messages) {
        State state = stateRef.get();
        if (!state.enabled) {
            return false;
        }

        if (!state.matchesAlias(rawCommand)) {
            return false;
        }

        if (state.mode == HexChatConfig.Help.Mode.CUSTOM) {
            messages.sendRawLinesWithoutPrefix(player, state.customLines, "help.custom-lines");
            return true;
        }

        if (isEssentialsPresent()) {
            return false;
        }

        if (warnedAboutMissingEssentials.compareAndSet(false, true)) {
            logger.warning("Tryb help.mode=ESSENTIALS aktywny, ale plugin Essentials nie jest załadowany.");
        }

        if (state.fallbackToCustomWhenEssentialsMissing) {
            messages.sendRawLinesWithoutPrefix(player, state.customLines, "help.custom-lines");
            return true;
        }

        messages.sendHelpUnavailable(player, state.unavailableMessage);
        return true;
    }

    private boolean isEssentialsPresent() {
        return pluginManager.getPlugin("Essentials") != null;
    }

    private record State(
            boolean enabled,
            HexChatConfig.Help.Mode mode,
            Set<String> aliases,
            java.util.List<String> customLines,
            boolean fallbackToCustomWhenEssentialsMissing,
            String unavailableMessage
    ) {
        private static State from(HexChatConfig.Help help) {
            Set<String> aliases = new HashSet<>();
            for (String alias : help.commandAliases()) {
                aliases.addAll(CommandNormalizationUtil.extractCandidates(alias));
            }

            if (aliases.isEmpty()) {
                aliases.add("help");
            }

            return new State(
                    help.enabled(),
                    help.mode(),
                    Set.copyOf(aliases),
                    java.util.List.copyOf(help.customLines()),
                    help.fallbackToCustomWhenEssentialsMissing(),
                    help.unavailableMessage()
            );
        }

        private boolean matchesAlias(String rawCommand) {
            Set<String> candidates = CommandNormalizationUtil.extractCandidates(rawCommand);
            for (String candidate : candidates) {
                if (aliases.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
