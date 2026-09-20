package hex.minigames.game.common;

import hex.minigames.util.Text;
import org.bukkit.entity.Player;

/** Consistent personal outcome and public announcement for every minigame. */
public final class RoundFeedback {
    private RoundFeedback() { }

    public static void show(Player player, boolean success, boolean sound) {
        player.sendTitle("", Text.color(success ? "&aSUKCES" : "&cPORAŻKA"), 0, 70, 10);
        if (sound) player.playSound(player.getLocation(), success ? "minecraft:entity.player.levelup"
                : "minecraft:entity.villager.no", 1.0f, success ? 1.2f : 0.8f);
    }

    public static String announcement(String name, boolean success) {
        return "&fGracz &6" + name + " &f- " + (success ? "&aSukces!" : "&cWyeliminowany!");
    }
}
