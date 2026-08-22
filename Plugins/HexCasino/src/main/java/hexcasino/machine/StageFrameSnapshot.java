package hexcasino.machine;

/** Immutable server-authored mapping between a BusDriver stage GUI revision and its logical decision window. */
public record StageFrameSnapshot(
        long gameId,
        int stageIndex,
        long stageFrameSeq,
        int windowId,
        int containerStateId,
        long stageElapsedMs,
        long frameStartNano,
        boolean answerWindowOpen,
        boolean withdrawActionAvailable
) {}
