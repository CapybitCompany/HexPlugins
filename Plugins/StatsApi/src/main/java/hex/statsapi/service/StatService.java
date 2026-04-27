package hex.statsapi.service;

import hex.statsapi.config.StatDefinition;
import hex.statsapi.config.StatRegistry;
import hex.statsapi.dto.StatResponse;
import hex.statsapi.repository.StatRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StatService {

    private final StatRegistry registry;
    private final StatRepository repository;

    public StatService(StatRegistry registry, StatRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    public Map<String, StatDefinition> availableDefinitions() {
        return registry.all();
    }

    public StatResponse leaderboard(String statId, int limit) {
        StatDefinition definition = requireDefinition(statId);

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<StatResponse.PlayerStatEntry> entries = repository.fetchLeaderboard(definition, safeLimit);

        return new StatResponse(definition.getId(), definition.getDisplayName(), entries);
    }

    public StatResponse.PlayerStatEntry playerStat(String statId, UUID uuid) {
        StatDefinition definition = requireDefinition(statId);
        return repository.fetchForPlayer(definition, uuid);
    }

    public StatDefinition requireDefinition(String statId) {
        StatDefinition definition = registry.get(statId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown statId: " + statId);
        }
        return definition;
    }
}

