package hexcasino.machine;

import hexcasino.config.CasinoConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure deterministic Skill Reel engine.
 *
 * There is intentionally no RNG API in this class. The final board is a pure function of the
 * frozen reel set and the player-selected STOP positions.
 */
public final class SlotEngine {

    public SlotOutcome outcomeFromStoppedPositions(DeterministicReelSet set,
                                                   SlotLayout layout,
                                                   int[] stoppedPositions) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(stoppedPositions, "stoppedPositions");
        if (stoppedPositions.length != layout.stopUnitCount()) {
            throw new IllegalArgumentException("stoppedPositions must match stop unit count");
        }
        String[] symbols = visibleSymbols(set, layout, stoppedPositions);
        return new SlotOutcome(layout.reels(), layout.rows(), symbols);
    }

    /** Returns row-major symbols for the requested deterministic reel positions. */
    public String[] visibleSymbols(DeterministicReelSet set, SlotLayout layout, int[] reelPositions) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(reelPositions, "reelPositions");
        if (reelPositions.length != layout.stopUnitCount()) {
            throw new IllegalArgumentException("reelPositions must match stop unit count");
        }

        if (layout.reels() == 1) {
            // The one-line layout is displayed horizontally but is represented as logical 1x3 so it
            // still contains exactly one geometric pattern. Each visible cell comes from its own
            // physical reel and is stopped independently.
            return new String[]{
                    set.reel(0).symbolAt(reelPositions[0]),
                    set.reel(1).symbolAt(reelPositions[1]),
                    set.reel(2).symbolAt(reelPositions[2])
            };
        }

        String[] out = new String[layout.cellCount()];
        for (int x = 0; x < layout.reels(); x++) {
            ReelStrip strip = set.reel(x);
            int position = reelPositions[x];
            for (int y = 0; y < layout.rows(); y++) {
                // TOP = p, MIDDLE = p-1, BOTTOM = p-2. Therefore a symbol that appears at TOP
                // moves to MIDDLE and then BOTTOM on subsequent frames as p increases.
                out[(y * layout.reels()) + x] = strip.symbolAt(position - y);
            }
        }
        return out;
    }

    public SlotSpinResult evaluate(SlotOutcome outcome,
                                   SlotLayout layout,
                                   CasinoConfig config,
                                   double chargedStake,
                                   Map<String, Double> rewardMultipliers) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(rewardMultipliers, "rewardMultipliers");
        if (outcome.reels() != layout.reels() || outcome.rows() != layout.rows()) {
            throw new IllegalArgumentException("Outcome layout does not match selected layout");
        }
        if (chargedStake < 0.0D) throw new IllegalArgumentException("chargedStake must be >= 0");

        List<WinningPattern> patterns = layout.winningPatterns();
        if (patterns.isEmpty()) throw new IllegalArgumentException("layout must have at least one winning pattern");
        double patternStake = chargedStake / patterns.size();
        double win = 0.0D;
        CasinoConfig.Symbol bestSymbol = null;
        List<WinningPatternHit> hits = new ArrayList<>();

        for (WinningPattern pattern : patterns) {
            GridPoint firstPoint = pattern.points().get(0);
            String first = outcome.symbol(firstPoint.x(), firstPoint.y());
            boolean same = true;
            for (int i = 1; i < pattern.points().size(); i++) {
                GridPoint point = pattern.points().get(i);
                if (!first.equals(outcome.symbol(point.x(), point.y()))) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;

            CasinoConfig.Symbol symbol = config.symbolsById().get(first);
            if (symbol == null) continue;
            double rewardMultiplier = Math.max(0.0D, rewardMultipliers.getOrDefault(symbol.id(), 0.0D));
            double payout = patternStake * rewardMultiplier;
            win += payout;
            hits.add(new WinningPatternHit(pattern, symbol, payout));
            if (bestSymbol == null
                    || rewardMultipliers.getOrDefault(symbol.id(), 0.0D)
                    > rewardMultipliers.getOrDefault(bestSymbol.id(), 0.0D)) {
                bestSymbol = symbol;
            }
        }

        return new SlotSpinResult(win, hits, bestSymbol, patternStake);
    }

}
