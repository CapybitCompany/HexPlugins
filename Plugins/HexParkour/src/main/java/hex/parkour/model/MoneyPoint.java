package hex.parkour.model;

import java.math.BigDecimal;

public record MoneyPoint(String id, BigDecimal value, CuboidRegion region, String subtitle) {
}
