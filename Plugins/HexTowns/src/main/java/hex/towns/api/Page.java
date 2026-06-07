package hex.towns.api;

import java.util.List;

public record Page<T>(List<T> items, String nextCursor) {
}