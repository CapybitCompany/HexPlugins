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

public final class TabCompleteFilterService {

    private final AtomicReference<State> stateRef;

    public TabCompleteFilterService(HexChatConfig initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig");
        this.stateRef = new AtomicReference<>(State.from(initialConfig.tabCompleteFilter()));
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        Objects.requireNonNull(updatedConfig, "updatedConfig");
        this.stateRef.set(State.from(updatedConfig.tabCompleteFilter()));
    }

    public void filterCommandSendSuggestions(Player player, Collection<String> commands) {
        State state = stateRef.get();
        if (!state.enabled || isBypassed(player, state)) {
            return;
        }

        commands.removeIf(state::matches);
    }

    public void filterTabCompletions(Player player, List<String> completions) {
        State state = stateRef.get();
        if (!state.enabled || isBypassed(player, state)) {
            return;
        }

        completions.removeIf(state::matches);
    }

    // Spójnie z CommandFilterService: OP oraz gracze z uprawnieniem administratora
    // pomijają filtr, tak samo jak posiadacze dedykowanego uprawnienia bypass.
    private boolean isBypassed(Player player, State state) {
        return player.isOp()
                || player.hasPermission(HexChatPermissions.ADMIN)
                || player.hasPermission(state.bypassPermission);
    }

    private record State(
            boolean enabled,
            String bypassPermission,
            Set<String> hiddenCommands
    ) {
        private static State from(HexChatConfig.TabCompleteFilter config) {
            Set<String> hidden = new HashSet<>();
            for (String command : config.hiddenCommands()) {
                hidden.addAll(CommandNormalizationUtil.extractCandidates(command));
            }

            return new State(config.enabled(), config.bypassPermission(), Set.copyOf(hidden));
        }

        private boolean matches(String rawValue) {
            Set<String> candidates = CommandNormalizationUtil.extractCandidates(rawValue);
            for (String candidate : candidates) {
                if (hiddenCommands.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
