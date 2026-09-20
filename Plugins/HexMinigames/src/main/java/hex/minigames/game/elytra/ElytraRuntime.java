package hex.minigames.game.elytra;
import java.util.*;

/** Each ring counts once per player, in any order; progress survives respawns. */
public final class ElytraRuntime {
    private final List<RingDefinition> rings;
    private final Map<UUID, Set<Integer>> passed = new HashMap<>();
    private final Map<UUID, Integer> places = new LinkedHashMap<>();
    private final Map<UUID, Long> finishTicks = new HashMap<>();
    public ElytraRuntime(List<RingDefinition> rings) { this.rings = List.copyOf(rings); }
    public int progress(UUID id) { return passed.getOrDefault(id, Set.of()).size(); }
    /** Zero-based indices of this player's completed rings, as an immutable snapshot. */
    public Set<Integer> passedRings(UUID id) { return Set.copyOf(passed.getOrDefault(id, Set.of())); }
    public int total() { return rings.size(); }
    public boolean finished(UUID id) { return places.containsKey(id); }
    public int place(UUID id) { return places.getOrDefault(id, 0); }
    public long finishTick(UUID id) { return finishTicks.getOrDefault(id, 0L); }
    public int points(UUID id) {
        int place = place(id);
        return place == 1 ? 3 : place >= 2 && place <= 5 ? 2 : place >= 6 && place <= 8 ? 1 : 0;
    }
    public int move(UUID id, boolean gliding, double x0, double y0, double z0, double x1, double y1, double z1, long tick) {
        if (!gliding || finished(id)) return 0;
        Set<Integer> completed = passed.computeIfAbsent(id, ignored -> new HashSet<>());
        int before = completed.size();
        for (int i = 0; i < rings.size(); i++) {
            if (!completed.contains(i) && rings.get(i).crossing(x0,y0,z0,x1,y1,z1).isPresent()) completed.add(i);
        }
        if (!rings.isEmpty() && completed.size() == rings.size()) {
            places.put(id, places.size() + 1);
            finishTicks.put(id, tick);
        }
        return completed.size() - before;
    }
}
