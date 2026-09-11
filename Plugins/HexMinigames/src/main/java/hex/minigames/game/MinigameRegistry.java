package hex.minigames.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MinigameRegistry {
    private final Map<String, MinigameFactory> factories = new LinkedHashMap<>();
    private Map<String, MinigameDefinition> definitions = Map.of();

    public void register(MinigameFactory factory) {
        factories.put(factory.id(), factory);
    }

    public void rebuild(Map<String, MinigameDefinition> configuredDefinitions) {
        Map<String, MinigameDefinition> rebuilt = new LinkedHashMap<>();
        for (MinigameDefinition definition : configuredDefinitions.values()) {
            MinigameFactory factory = factories.get(definition.id());
            rebuilt.put(definition.id(), definition.withImplementation(factory != null, factory != null && factory.internal()));
        }
        for (MinigameFactory factory : factories.values()) {
            if (rebuilt.containsKey(factory.id())) continue;
            MinigameDefinition definition = new MinigameDefinition(
                    factory.id(),
                    factory.id(),
                    factory.internal(),
                    true,
                    factory.internal(),
                    1,
                    0,
                    1,
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    10,
                    Map.of(),
                    "internal"
            );
            rebuilt.put(definition.id(), definition);
        }
        definitions = Map.copyOf(rebuilt);
    }

    public Optional<MinigameDefinition> definition(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Collection<MinigameDefinition> definitions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(MinigameDefinition::id))
                .toList();
    }

    public int configuredRealGameCount() {
        int count = 0;
        for (MinigameDefinition definition : definitions.values()) {
            if (!definition.internal()) count++;
        }
        return count;
    }

    public Optional<Minigame> create(String id) {
        MinigameFactory factory = factories.get(id);
        return factory == null ? Optional.empty() : Optional.of(factory.create());
    }

    public List<MinigameDefinition> eligible(int playerCount, boolean includeInternal, boolean ignoreEnabled) {
        List<MinigameDefinition> out = new ArrayList<>();
        for (MinigameDefinition definition : definitions.values()) {
            if (!definition.implemented()) continue;
            if (definition.internal() && !includeInternal) continue;
            if (!ignoreEnabled && !definition.enabled()) continue;
            if (!definition.playerCountAllowed(playerCount)) continue;
            if (!definition.internal() && definition.region().isEmpty()) continue;
            if (!definition.internal() && definition.participantSpawns().isEmpty()) continue;
            if (!definition.internal() && definition.spectatorSpawn().isEmpty()) continue;
            Minigame game = create(definition.id()).orElse(null);
            if (game == null) continue;
            if (!game.availability(definition, playerCount).available()) continue;
            out.add(definition);
        }
        return out;
    }
}
