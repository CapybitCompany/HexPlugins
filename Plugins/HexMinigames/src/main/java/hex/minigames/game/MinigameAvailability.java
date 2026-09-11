package hex.minigames.game;

public record MinigameAvailability(boolean available, String reason) {
    public static MinigameAvailability ok() {
        return new MinigameAvailability(true, "");
    }

    public static MinigameAvailability unavailable(String reason) {
        return new MinigameAvailability(false, reason == null ? "" : reason);
    }
}
