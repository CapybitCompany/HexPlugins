package hexcasino.machine;

import hexcasino.config.CasinoConfig;

import java.util.List;

public record SlotSpinResult(
        double win,
        List<WinningPatternHit> hits,
        CasinoConfig.Symbol bestSymbol,
        double patternStake
) {
    public SlotSpinResult {
        hits = List.copyOf(hits);
    }

    public int winningPatternCount() {
        return hits.size();
    }
}
