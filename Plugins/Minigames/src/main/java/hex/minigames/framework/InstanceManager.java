package hex.minigames.framework;

import hex.minigames.framework.config.GameTypeConfig;
import hex.minigames.framework.map.MapProvider;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InstanceManager {

    private final JavaPlugin plugin;
    private final MapProvider mapProvider;
    private final ArenaIdGenerator idGenerator = new ArenaIdGenerator();

    private final Map<String, GameTypeRegistration> registrations = new HashMap<>();
    private final Map<String, List<GameInstance>> instancesByType = new HashMap<>();
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    public InstanceManager(JavaPlugin plugin, MapProvider mapProvider) {
        this.plugin = plugin;
        this.mapProvider = mapProvider;
    }

    public void registerGameType(String gameTypeId, GameTypeConfig config, GameInstanceFactory factory) {
        GameTypeRegistration reg = new GameTypeRegistration(gameTypeId, config, factory);
        registrations.put(gameTypeId, reg);
        instancesByType.putIfAbsent(gameTypeId, new ArrayList<>());
    }

    public List<String> gameTypes() {
        return registrations.keySet().stream().sorted().toList();
    }

    public Optional<PlayerSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public JoinResult join(Player player, String gameTypeId) {
        GameTypeRegistration reg = registrations.get(gameTypeId);
        if (reg == null) {
            return JoinResult.fail("lobby.join.unknownType");
        }
        if (sessions.containsKey(player.getUniqueId())) {
            return JoinResult.fail("lobby.join.already");
        }

        GameInstance target = findJoinable(gameTypeId)
                .orElseGet(() -> createInstanceIfPossible(reg));

        if (target == null) {
            return JoinResult.fail("lobby.join.full");
        }

        if (!target.join(player)) {
            return JoinResult.fail("lobby.join.full");
        }

        sessions.put(player.getUniqueId(), new PlayerSession(player.getUniqueId(), gameTypeId, target.instanceId()));
        return JoinResult.ok(target);
    }

    public boolean leave(Player player) {
        return leave(player.getUniqueId());
    }

    public boolean leave(UUID playerId) {
        PlayerSession session = sessions.remove(playerId);
        if (session == null) {
            return false;
        }

        GameInstance instance = findInstanceById(session.gameTypeId(), session.instanceId());
        if (instance != null) {
            instance.leave(playerId);
        }
        return true;
    }

    public void tick() {
        for (Map.Entry<String, List<GameInstance>> entry : instancesByType.entrySet()) {
            for (GameInstance instance : entry.getValue()) {
                instance.tick();

                if (instance.needsReset()) {
                    mapProvider.reset(instance);
                    instance.completeReset();
                }
            }
        }
    }

    public List<GameInstance> instances(String gameTypeId) {
        return List.copyOf(instancesByType.getOrDefault(gameTypeId, List.of()));
    }

    public Map<String, ModeStatusSnapshot> collectModeStatus() {
        Map<String, ModeStatusSnapshot> out = new LinkedHashMap<>();

        for (String gameType : registrations.keySet()) {
            List<GameInstance> instances = instancesByType.getOrDefault(gameType, List.of());

            int waiting = instances.stream()
                    .filter(i -> i.state() == GameState.LOBBY || i.state() == GameState.COUNTDOWN)
                    .mapToInt(GameInstance::playersCount)
                    .sum();

            int inGame = instances.stream()
                    .filter(i -> i.state() == GameState.INGAME)
                    .mapToInt(GameInstance::playersCount)
                    .sum();

            boolean joinable = instances.stream().anyMatch(GameInstance::isJoinable);

            // Uzywamy sumy max slots aktywnych instancji danego typu.
            int maxPlayers = instances.stream().mapToInt(GameInstance::maxPlayers).sum();

            out.put(gameType, new ModeStatusSnapshot(gameType, waiting, inGame, joinable, maxPlayers));
        }

        return out;
    }

    private Optional<GameInstance> findJoinable(String gameTypeId) {
        return instancesByType.getOrDefault(gameTypeId, List.of()).stream()
                .filter(GameInstance::isJoinable)
                .sorted(Comparator.comparing(GameInstance::state)
                        .thenComparingInt(GameInstance::playersCount).reversed())
                .findFirst();
    }

    private GameInstance createInstanceIfPossible(GameTypeRegistration reg) {
        List<GameInstance> instances = instancesByType.get(reg.gameTypeId());
        if (instances.size() >= reg.config().maxInstances()) {
            return null;
        }

        String worldName = mapProvider.pickMap(reg.gameTypeId(), reg.config());
        String instanceId = idGenerator.next(reg.gameTypeId());

        GameInstance instance = reg.factory().create(instanceId, reg.gameTypeId(), worldName, reg.config());
        instances.add(instance);

        plugin.getLogger().info("[Minigames] Created instance " + instanceId + " on map " + worldName);
        return instance;
    }

    private GameInstance findInstanceById(String gameTypeId, String instanceId) {
        return instancesByType.getOrDefault(gameTypeId, List.of()).stream()
                .filter(i -> i.instanceId().equals(instanceId))
                .findFirst()
                .orElse(null);
    }
}

