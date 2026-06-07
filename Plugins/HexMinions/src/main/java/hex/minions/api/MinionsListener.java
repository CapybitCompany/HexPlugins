package hex.minions.api;

public interface MinionsListener {
    default void onMinionChanged(MinionView minion) {
    }
}

