package hexcasino.machine;

import java.util.Arrays;
import java.util.Objects;

public record SlotOutcome(int reels, int rows, String[] symbols) {

    public SlotOutcome {
        if (rows != 3) {
            throw new IllegalArgumentException("Slot outcome rows must be exactly 3");
        }
        if (reels != 1 && reels != 3 && reels != 5) {
            throw new IllegalArgumentException("Slot outcome reels must be 1, 3 or 5");
        }
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.length != reels * rows) {
            throw new IllegalArgumentException("Slot outcome symbol count must equal reels * rows");
        }
        symbols = Arrays.copyOf(symbols, symbols.length);
        for (String symbol : symbols) {
            Objects.requireNonNull(symbol, "outcome symbol");
        }
    }

    @Override
    public String[] symbols() {
        return Arrays.copyOf(symbols, symbols.length);
    }

    public String symbol(int index) {
        return symbols[index];
    }

    public String symbol(int x, int y) {
        if (x < 0 || x >= reels || y < 0 || y >= rows) {
            throw new IndexOutOfBoundsException("Grid point outside outcome: " + x + "," + y);
        }
        return symbols[(y * reels) + x];
    }
}
