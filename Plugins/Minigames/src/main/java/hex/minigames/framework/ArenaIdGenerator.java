package hex.minigames.framework;

import java.util.concurrent.atomic.AtomicLong;

public final class ArenaIdGenerator {

    private final AtomicLong seq = new AtomicLong(1L);

    public String next(String gameTypeId) {
        long id = seq.getAndIncrement();
        return gameTypeId + "-" + id;
    }
}

