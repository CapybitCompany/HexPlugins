package hexcasino.machine;

import hexcasino.config.CasinoConfig;

import java.util.Objects;

public record WinningPatternHit(
        WinningPattern pattern,
        CasinoConfig.Symbol symbol,
        double payout
) {
    public WinningPatternHit {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(symbol, "symbol");
        if (payout < 0.0D) {
            throw new IllegalArgumentException("payout must be >= 0");
        }
    }
}
