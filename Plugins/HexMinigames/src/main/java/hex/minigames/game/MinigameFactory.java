package hex.minigames.game;

public interface MinigameFactory {
    String id();
    boolean internal();
    Minigame create();
}
