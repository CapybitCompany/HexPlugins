package hex.minigames.framework;

import org.bukkit.entity.Player;

public interface GameBehaviour {

    default void onLobbyJoin(Player player, GameInstance instance) {
    }

    default void onGameStart(GameInstance instance) {
    }

    default void onGameEnd(GameInstance instance) {
    }
}

