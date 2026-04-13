package hex.minigames.framework;

public record JoinResult(boolean success, String messageKey, GameInstance instance) {

    public static JoinResult ok(GameInstance instance) {
        return new JoinResult(true, "lobby.join.ok", instance);
    }

    public static JoinResult fail(String messageKey) {
        return new JoinResult(false, messageKey, null);
    }
}

