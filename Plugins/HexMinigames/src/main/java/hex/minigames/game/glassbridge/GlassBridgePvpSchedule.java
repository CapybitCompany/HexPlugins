package hex.minigames.game.glassbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Three-second PvP windows separated by random 15–30 second peaceful intervals. */
public final class GlassBridgePvpSchedule {
    private static final int DURATION_TICKS = 60;
    private final List<Long> starts;

    public GlassBridgePvpSchedule(long roundTicks, Random random) {
        List<Long> scheduled = new ArrayList<>();
        long start = 300 + random.nextInt(301);
        while (start + DURATION_TICKS < roundTicks) {
            scheduled.add(start);
            start += DURATION_TICKS + 300 + random.nextInt(301);
        }
        starts = List.copyOf(scheduled);
    }

    public boolean active(long tick) {
        return starts.stream().anyMatch(start -> tick >= start && tick < start + DURATION_TICKS);
    }

    public List<Long> starts() {
        return starts;
    }
}
