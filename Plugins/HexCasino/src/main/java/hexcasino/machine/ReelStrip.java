package hexcasino.machine;

import java.util.List;
import java.util.Objects;

/** Immutable, pre-generated reel strip. Runtime never shuffles or randomizes it. */
public record ReelStrip(String id, List<String> symbols) {
    public ReelStrip {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("reel strip cannot be empty");
        }
        symbols = List.copyOf(symbols);
        for (String symbol : symbols) {
            Objects.requireNonNull(symbol, "reel symbol");
        }
    }

    public int size() {
        return symbols.size();
    }

    public String symbolAt(int position) {
        return symbols.get(Math.floorMod(position, symbols.size()));
    }
}
