package hex.minigames.game.tag;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.util.*;

/** A temporary shared scoreboard supplies glow colors and is restored after the round. */
public final class TagRoleDisplay {
    private final Map<UUID, Saved> saved = new LinkedHashMap<>();
    private Scoreboard board;
    private Team taggers;
    private Team runners;

    public void show(Collection<Player> players, TagRuntime runtime) {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        taggers = board.registerNewTeam("hex_tag_red");
        runners = board.registerNewTeam("hex_tag_green");
        taggers.color(NamedTextColor.RED);
        runners.color(NamedTextColor.GREEN);
        // Damage is cancelled by the game; friendly fire must still produce tag hit events.
        taggers.setAllowFriendlyFire(true);
        runners.setAllowFriendlyFire(true);
        for (Player player : players) {
            saved.put(player.getUniqueId(), new Saved(player, player.getScoreboard(), player.isGlowing()));
            player.setScoreboard(board);
            player.setGlowing(true);
            update(player, runtime.isTagger(player.getUniqueId()));
        }
    }

    public void update(Player player, boolean tagger) {
        if (board == null) return;
        (tagger ? runners : taggers).removeEntry(player.getName());
        (tagger ? taggers : runners).addEntry(player.getName());
    }

    public void restore(UUID id) {
        Saved original = saved.remove(id);
        if (original == null) return;
        taggers.removeEntry(original.player().getName());
        runners.removeEntry(original.player().getName());
        original.player().setGlowing(original.glowing());
        if (original.player().isOnline()) original.player().setScoreboard(original.scoreboard());
    }

    public void clear() {
        for (UUID id : List.copyOf(saved.keySet())) restore(id);
        if (board != null) {
            taggers.unregister();
            runners.unregister();
            board = null;
        }
    }

    private record Saved(Player player, Scoreboard scoreboard, boolean glowing) { }
}
