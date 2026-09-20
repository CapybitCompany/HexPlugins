package hex.minigames.game.common;

import hex.minigames.game.RoundContext;
import hex.minigames.util.Text;
import org.bukkit.entity.Player;

/** Shared action-bar countdown for games without additional action-bar statistics. */
public final class RoundClock {
    private RoundClock() { }

    public static String remaining(int durationSeconds, long elapsedTicks) {
        long seconds = Math.max(0, (durationSeconds * 20L - elapsedTicks + 19) / 20);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    public static void show(RoundContext context) {
        if (context.elapsedTicks() % 2 != 0) return;
        var message = Text.component("&fCzas: &e" + remaining(context.definition().roundTimeSeconds(), context.elapsedTicks()));
        for (Player player : context.onlineParticipants()) player.sendActionBar(message);
    }
}
