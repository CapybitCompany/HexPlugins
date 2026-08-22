package hexcasino.machine;

import java.util.Arrays;

/** Immutable server-side record of one logical visual frame / container revision binding. */
public record FrameSnapshot(
        long gameId,
        long frameSeq,
        int containerStateId,
        long frameStartNano,
        long frameEndNano,
        int[] reelPositions,
        String[] visibleSymbols
) {
    public FrameSnapshot {
        reelPositions = Arrays.copyOf(reelPositions, reelPositions.length);
        visibleSymbols = Arrays.copyOf(visibleSymbols, visibleSymbols.length);
    }

    @Override public int[] reelPositions() { return Arrays.copyOf(reelPositions, reelPositions.length); }
    @Override public String[] visibleSymbols() { return Arrays.copyOf(visibleSymbols, visibleSymbols.length); }
    public int reelPosition(int index) { return reelPositions[index]; }

    public FrameSnapshot withContainerStateId(int stateId) {
        return new FrameSnapshot(gameId, frameSeq, stateId, frameStartNano, frameEndNano, reelPositions, visibleSymbols);
    }
}
