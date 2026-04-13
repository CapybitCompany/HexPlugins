package hex.minigames.framework;

import hex.core.api.HexApi;
import hex.minigames.framework.config.GameTypeConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class GameInstance {

    private final JavaPlugin plugin;
    private final HexApi api;
    private final GameBehaviour behaviour;

    private final String instanceId;
    private final String gameTypeId;
    private final String worldName;
    private final int maxPlayers;
    private final int minPlayers;
    private final int countdownSeconds;
    private final GameTypeConfig config;

    private final Set<UUID> players = new LinkedHashSet<>();

    private GameState state = GameState.LOBBY;
    private int countdownLeft;
    private int inGameSecondsLeft = 120;
    private int endSecondsLeft = 8;

    public GameInstance(
            JavaPlugin plugin,
            HexApi api,
            GameBehaviour behaviour,
            String instanceId,
            String gameTypeId,
            String worldName,
            GameTypeConfig config
    ) {
        this.plugin = plugin;
        this.api = api;
        this.behaviour = behaviour;
        this.instanceId = instanceId;
        this.gameTypeId = gameTypeId;
        this.worldName = worldName;
        this.maxPlayers = config.maxPlayers();
        this.minPlayers = config.minPlayers();
        this.countdownSeconds = config.countdownSeconds();
        this.config = config;
        this.countdownLeft = countdownSeconds;
    }

    public String instanceId() { return instanceId; }
    public String gameTypeId() { return gameTypeId; }
    public String worldName() { return worldName; }
    public GameState state() { return state; }
    public int maxPlayers() { return maxPlayers; }
    public int minPlayers() { return minPlayers; }
    public int countdownSeconds() { return countdownSeconds; }
    public int playersCount() { return players.size(); }
    public Set<UUID> players() { return Set.copyOf(players); }

    public boolean waiting() {
        return state == GameState.LOBBY || state == GameState.COUNTDOWN;
    }

    public boolean inGame() {
        return state == GameState.INGAME;
    }

    public boolean isJoinable() {
        return state.isJoinable() && players.size() < maxPlayers;
    }

    public boolean join(Player player) {
        if (!isJoinable()) {
            return false;
        }
        if (!players.add(player.getUniqueId())) {
            return true;
        }

        teleportToLobbySpawn(player);
        behaviour.onLobbyJoin(player, this);
        return true;
    }

    public void leave(UUID playerId) {
        players.remove(playerId);

        if (state == GameState.COUNTDOWN && players.size() < minPlayers) {
            state = GameState.LOBBY;
            countdownLeft = countdownSeconds;
        }
    }

    public void tick() {
        switch (state) {
            case LOBBY -> tickLobby();
            case COUNTDOWN -> tickCountdown();
            case INGAME -> tickInGame();
            case END -> tickEnd();
            case RESET -> {
                // reset wykonuje manager przez MapProvider + completeReset()
            }
        }
    }

    public boolean needsReset() {
        return state == GameState.RESET;
    }

    public void completeReset() {
        countdownLeft = countdownSeconds;
        inGameSecondsLeft = 120;
        endSecondsLeft = 8;
        state = GameState.LOBBY;
    }

    private void tickLobby() {
        sendActionbar("lobby.actionbar.waiting",
                gameTypeId,
                Integer.toString(players.size()),
                Integer.toString(maxPlayers),
                Integer.toString(minPlayers),
                worldName
        );

        if (players.size() >= minPlayers) {
            state = GameState.COUNTDOWN;
            countdownLeft = countdownSeconds;
        }
    }

    private void tickCountdown() {
        if (players.size() < minPlayers) {
            state = GameState.LOBBY;
            countdownLeft = countdownSeconds;
            return;
        }

        sendSubtitle("lobby.countdown.subtitle", Integer.toString(countdownLeft));
        countdownLeft--;

        if (countdownLeft <= 0) {
            state = GameState.INGAME;
            inGameSecondsLeft = 120;
            behaviour.onGameStart(this);
        }
    }

    private void tickInGame() {
        inGameSecondsLeft--;

        if (players.size() <= 1 || inGameSecondsLeft <= 0) {
            state = GameState.END;
            endSecondsLeft = 8;
            behaviour.onGameEnd(this);
        }
    }

    private void tickEnd() {
        endSecondsLeft--;
        if (endSecondsLeft <= 0) {
            state = GameState.RESET;
        }
    }

    public void broadcastTemplate(String templateKey, String... args) {
        Component c = api.ui().render(templateKey, args);
        forEachOnlinePlayer(p -> p.sendMessage(c));
    }

    private void sendActionbar(String templateKey, String... args) {
        Component c = api.ui().render(templateKey, args);
        forEachOnlinePlayer(p -> p.sendActionBar(c));
    }

    private void sendSubtitle(String templateKey, String... args) {
        Component subtitle = api.ui().render(templateKey, args);

        Title title = Title.title(
                Component.empty(),
                subtitle,
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200))
        );

        forEachOnlinePlayer(p -> p.showTitle(title));
    }

    private void forEachOnlinePlayer(java.util.function.Consumer<Player> consumer) {
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                consumer.accept(p);
            }
        }
    }

    private void teleportToLobbySpawn(Player player) {
        try {
            player.teleport(config.lobbySpawn().toLocation());
        } catch (Exception ex) {
            plugin.getLogger().warning("Cannot teleport to lobby spawn for " + instanceId + ": " + ex.getMessage());
        }
    }
}

