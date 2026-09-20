package hex.minigames.score;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import java.util.Locale;
import java.util.regex.Pattern;
/** Resolves historical top five and personal points from memory. */
public final class MinigamesPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern TOP = Pattern.compile("top_?([1-5])(?:_(name|points))?");
    private final Plugin plugin;
    private final ScoreService scores;
    public MinigamesPlaceholderExpansion(Plugin plugin, ScoreService scores) { this.plugin = plugin; this.scores = scores; }
    @Override public String getIdentifier() { return "hexminigames"; }
    @Override public String getAuthor() { return "HexDevTeam"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("points")) return String.valueOf(player == null ? 0 : scores.getGlobalPoints(player.getUniqueId()));
        var match = TOP.matcher(key);
        if (!match.matches()) return null;
        int index = Integer.parseInt(match.group(1)) - 1;
        var top = scores.top(5);
        boolean pointsOnly = "points".equals(match.group(2));
        if (index >= top.size()) return pointsOnly ? "0" : "-";
        var entry = top.get(index);
        String name = entry.name() == null ? entry.playerId().toString() : entry.name();
        if (pointsOnly) return String.valueOf(entry.points());
        return "name".equals(match.group(2)) ? name : name + " - " + entry.points();
    }
}
