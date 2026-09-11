package hex.minigames.game;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

public final class DebugMinigame implements Minigame {
    public static final String ID = "debug";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        Map<UUID, PlayerRoundResult> results = new LinkedHashMap<>();
        int placement = 1;
        for (UUID playerId : context.participants()) {
            results.put(playerId, new PlayerRoundResult(1, OptionalInt.of(placement++), true, false, Map.of("debug", "true")));
        }
        return new RoundResult(results, Map.of("debug", "true", "reason", reason.name()));
    }
}
