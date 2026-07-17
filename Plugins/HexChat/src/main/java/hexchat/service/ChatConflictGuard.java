package hexchat.service;

import hexchat.config.HexChatConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Wykrywa inne pluginy, które również zarządzają czatem, aby ostrzec administratora
 * przed konfliktami (typowa przyczyna błędów typu "Chat Verification Error").
 * <p>
 * HexChat sam w sobie jest bezpieczny dla podpisanego czatu — anuluje wiadomości lub
 * podmienia jedynie render/wyświetlanie i nigdy nie modyfikuje podpisanej treści. Guard
 * służy do diagnostyki oraz (opcjonalnie) do wymuszenia renderu HexChat.
 */
public final class ChatConflictGuard {

    private final AtomicReference<State> stateRef;
    private final AtomicBoolean conflictDetected = new AtomicBoolean(false);

    public ChatConflictGuard(HexChatConfig initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig");
        this.stateRef = new AtomicReference<>(State.from(initialConfig.chat().conflictGuard()));
    }

    public void updateConfig(HexChatConfig updatedConfig) {
        Objects.requireNonNull(updatedConfig, "updatedConfig");
        this.stateRef.set(State.from(updatedConfig.chat().conflictGuard()));
    }

    public boolean isEnabled() {
        return stateRef.get().enabled;
    }

    public boolean shouldWarn() {
        return stateRef.get().warnOnConflict;
    }

    public boolean shouldEnforceFormat() {
        return stateRef.get().enforceFormat;
    }

    /** Ustawiane przez plugin po wykryciu (lub braku) konfliktu przy starcie/reloadzie. */
    public void setConflictDetected(boolean detected) {
        conflictDetected.set(detected);
    }

    public boolean isConflictDetected() {
        return conflictDetected.get();
    }

    /**
     * Czy HexChat powinien narzucić własny format renderowania.
     * Gdy {@code enforce-format=true} — zawsze. W przeciwnym razie HexChat ustępuje
     * (nie renderuje formatu), jeśli wykryto inny plugin czatu, aby mu nie przeszkadzać.
     */
    public boolean shouldRenderFormat() {
        return stateRef.get().enforceFormat || !conflictDetected.get();
    }

    /**
     * Analizuje potencjalne konflikty czatu i rozdziela je semantycznie:
     * <ul>
     *   <li>{@link ConflictReport#formatConflicts()} — znane pluginy formatujące czat
     *       (z listy {@code known-chat-plugins}). Tylko one powodują ustąpienie formatu.</li>
     *   <li>{@link ConflictReport#listenerWarnings()} — inne, nieznane pluginy nasłuchujące
     *       AsyncChatEvent. Jedynie diagnostyka (mogą tylko logować/moderować) — nie wyłączają
     *       formatu HexChat.</li>
     * </ul>
     *
     * @param selfPluginName           nazwa własnego pluginu (pomijana)
     * @param chatEventListenerPlugins nazwy pluginów nasłuchujących AsyncChatEvent
     * @param installedPluginNames     nazwy wszystkich zainstalowanych pluginów
     */
    public ConflictReport analyze(
            String selfPluginName,
            Collection<String> chatEventListenerPlugins,
            Collection<String> installedPluginNames
    ) {
        State state = stateRef.get();
        String self = selfPluginName == null ? "" : selfPluginName;

        Set<String> knownInstalled = installedPluginNames.stream()
                .filter(Objects::nonNull)
                .filter(name -> !name.equalsIgnoreCase(self))
                .filter(name -> state.knownChatPlugins.contains(name.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toCollection(TreeSet::new));

        // Nieznane nasłuchy: inne pluginy na AsyncChatEvent, które NIE są znanymi pluginami czatu.
        Set<String> unknownListeners = chatEventListenerPlugins.stream()
                .filter(Objects::nonNull)
                .filter(name -> !name.equalsIgnoreCase(self))
                .filter(name -> !state.knownChatPlugins.contains(name.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toCollection(TreeSet::new));

        List<String> formatConflicts = new ArrayList<>();
        for (String plugin : knownInstalled) {
            formatConflicts.add("Wykryto znany plugin formatujący czat: '" + plugin
                    + "'. Może zarządzać formatem czatu równolegle z HexChat.");
        }

        List<String> listenerWarnings = new ArrayList<>();
        for (String plugin : unknownListeners) {
            listenerWarnings.add("Inny plugin nasłuchuje AsyncChatEvent: '" + plugin
                    + "' (diagnostyka — może jedynie logować/moderować, nie wyłącza formatu HexChat).");
        }

        return new ConflictReport(List.copyOf(formatConflicts), List.copyOf(listenerWarnings));
    }

    /**
     * Wynik analizy konfliktów. {@code formatConflicts} to realne konflikty formatu
     * (znane pluginy czatu); {@code listenerWarnings} to jedynie diagnostyka.
     */
    public record ConflictReport(List<String> formatConflicts, List<String> listenerWarnings) {
        public ConflictReport {
            formatConflicts = List.copyOf(formatConflicts);
            listenerWarnings = List.copyOf(listenerWarnings);
        }

        public boolean hasFormatConflict() {
            return !formatConflicts.isEmpty();
        }
    }

    private record State(
            boolean enabled,
            boolean warnOnConflict,
            boolean enforceFormat,
            Set<String> knownChatPlugins
    ) {
        private static State from(HexChatConfig.ConflictGuard config) {
            Set<String> known = config.knownChatPlugins().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT).trim())
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
            return new State(config.enabled(), config.warnOnConflict(), config.enforceFormat(), known);
        }
    }
}
