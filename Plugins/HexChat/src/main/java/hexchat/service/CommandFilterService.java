package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.permission.HexChatPermissions;
import hexchat.util.CommandNormalizationUtil;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class CommandFilterService {

    private final AtomicReference<State> stateRef;

    public CommandFilterService(HexChatConfig initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig");
        this.stateRef = new AtomicReference<>(State.from(initialConfig.commandFilter()));
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        Objects.requireNonNull(updatedConfig, "updatedConfig");
        this.stateRef.set(State.from(updatedConfig.commandFilter()));
    }

    public boolean isBlocked(Player player, String rawCommand) {
        State state = stateRef.get();
        if (!state.enabled) {
            return false;
        }
        if (isBypassed(player, state)) {
            return false;
        }

        Set<String> candidates = CommandNormalizationUtil.extractCandidates(rawCommand);
        if (candidates.isEmpty()) {
            return false;
        }

        for (String candidate : candidates) {
            if (state.allowedCommands.contains(candidate)) {
                return false;
            }
        }
        return true;
    }

    public String blockedMessage() {
        return stateRef.get().blockedMessage;
    }

    public void filterCommandSendList(Player player, Collection<String> commands) {
        State state = stateRef.get();
        if (!state.enabled) {
            return;
        }
        if (isBypassed(player, state)) {
            return;
        }

        commands.removeIf(command -> shouldRemoveFromSendList(state, command));
    }

    public void filterTabCompletions(Player player, List<String> completions) {
        State state = stateRef.get();
        if (!state.enabled) {
            return;
        }
        if (isBypassed(player, state)) {
            return;
        }

        completions.removeIf(completion -> shouldRemoveFromSendList(state, completion));
    }

    private boolean shouldRemoveFromSendList(State state, String rawCommand) {
        String normalized = CommandNormalizationUtil.normalizeToSingleToken(rawCommand);
        if (normalized.isBlank()) {
            return false;
        }

        if (state.hideNamespacedSuggestions && normalized.contains(":")) {
            return !state.allowedNamespacedSuggestions.contains(normalized);
        }

        Set<String> candidates = CommandNormalizationUtil.extractCandidates(normalized);
        for (String candidate : candidates) {
            if (state.allowedCommands.contains(candidate)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBypassed(Player player, State state) {
        return player.isOp()
                || player.hasPermission(HexChatPermissions.ADMIN)
                || player.hasPermission(state.bypassPermission);
    }

    private record State(
            boolean enabled,
            String bypassPermission,
            Set<String> allowedCommands,
            String blockedMessage,
            boolean hideNamespacedSuggestions,
            Set<String> allowedNamespacedSuggestions
    ) {
        private static State from(HexChatConfig.CommandFilter config) {
            Set<String> allowed = new HashSet<>();
            for (String command : config.allowedCommands()) {
                allowed.addAll(CommandNormalizationUtil.extractCandidates(command));
            }

            Set<String> allowedNamespaced = new HashSet<>();
            for (String command : config.allowedNamespacedSuggestions()) {
                String normalized = CommandNormalizationUtil.normalizeToSingleToken(command);
                if (!normalized.isBlank()) {
                    allowedNamespaced.add(normalized);
                }
            }

            return new State(
                    config.enabled(),
                    config.bypassPermission(),
                    Set.copyOf(allowed),
                    config.blockedMessage(),
                    config.hideNamespacedSuggestions(),
                    Set.copyOf(allowedNamespaced)
            );
        }
    }
}
