package hexcasino.machine;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** One of the fixed 1..100 deterministic reel sets. */
public record DeterministicReelSet(int index, List<ReelStrip> reels, int[] startPositions) {
    public DeterministicReelSet {
        if (index < 1) {
            throw new IllegalArgumentException("reel set index must be >= 1");
        }
        Objects.requireNonNull(reels, "reels");
        Objects.requireNonNull(startPositions, "startPositions");
        if (reels.size() < 5) {
            throw new IllegalArgumentException("reel set must contain at least 5 physical reels");
        }
        if (startPositions.length != reels.size()) {
            throw new IllegalArgumentException("startPositions must match reel count");
        }
        reels = List.copyOf(reels);
        startPositions = Arrays.copyOf(startPositions, startPositions.length);
    }

    @Override
    public int[] startPositions() {
        return Arrays.copyOf(startPositions, startPositions.length);
    }

    public ReelStrip reel(int physicalReel) {
        return reels.get(physicalReel);
    }

    public int startPosition(int physicalReel) {
        return startPositions[physicalReel];
    }
}
