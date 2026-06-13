package hex.auctionbazaar.bazaar.service;

import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure, deterministic price calculation. No state.
 *
 * mid     = clamp(basePrice * (referenceStock / max(stock, 1)) ^ elasticity, min, max)
 * buyRaw  = mid * (1 + spread/200)
 * sellRaw = mid * (1 - spread/200)
 *
 * Smoothing: |newPrice - lastPrice| / lastPrice &lt;= maxStep/100
 *            -&gt; otherwise clamp against lastPrice.
 */
public final class BazaarPricer {

    private static final MathContext MC = new MathContext(20);
    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO_HUNDRED = new BigDecimal("200");

    private BazaarPricer() {
    }

    public static BazaarPrice compute(BazaarItemConfig item,
                                       BazaarConfig.Pricing pricing,
                                       long stock,
                                       BigDecimal lastBuyPrice,
                                       BigDecimal lastSellPrice) {
        BigDecimal mid = midPrice(item, pricing, stock);
        BigDecimal spreadHalf = pricing.buySellSpreadPercent().divide(TWO_HUNDRED, MC);
        BigDecimal buyRaw = mid.multiply(BigDecimal.ONE.add(spreadHalf), MC);
        BigDecimal sellRaw = mid.multiply(BigDecimal.ONE.subtract(spreadHalf), MC);

        BigDecimal buy = applyStep(buyRaw, lastBuyPrice, pricing.maxStepPerTransactionPercent());
        BigDecimal sell = applyStep(sellRaw, lastSellPrice, pricing.maxStepPerTransactionPercent());

        // Safety clamp against min/max after smoothing.
        buy = clamp(buy, item.minPrice(), item.maxPrice());
        sell = clamp(sell, item.minPrice(), item.maxPrice());

        // sellPrice must never exceed buyPrice (anti-arbitrage).
        if (sell.compareTo(buy) > 0) {
            sell = buy;
        }

        return new BazaarPrice(round(mid), round(buy), round(sell));
    }

    public static BigDecimal midPrice(BazaarItemConfig item,
                                       BazaarConfig.Pricing pricing,
                                       long stock) {
        long safeStock = Math.max(1L, stock);
        BigDecimal ratio = pricing.referenceStock()
                .divide(new BigDecimal(safeStock), MC);
        // basePrice * ratio^elasticity via Math.pow on double - good enough.
        double base = item.basePrice().doubleValue();
        double ratioD = ratio.doubleValue();
        double elasticity = pricing.elasticity().doubleValue();
        double raw = base * Math.pow(ratioD, elasticity);
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            raw = item.basePrice().doubleValue();
        }
        BigDecimal computed = new BigDecimal(raw, MC);
        return clamp(computed, item.minPrice(), item.maxPrice());
    }

    private static BigDecimal applyStep(BigDecimal newPrice,
                                         BigDecimal lastPrice,
                                         BigDecimal maxStepPercent) {
        if (lastPrice == null || lastPrice.signum() <= 0) {
            return newPrice;
        }
        if (maxStepPercent == null || maxStepPercent.signum() <= 0) {
            return newPrice;
        }
        BigDecimal maxStep = lastPrice.multiply(maxStepPercent, MC).divide(HUNDRED, MC);
        BigDecimal lower = lastPrice.subtract(maxStep);
        BigDecimal upper = lastPrice.add(maxStep);
        if (newPrice.compareTo(upper) > 0) return upper;
        if (newPrice.compareTo(lower) < 0) return lower;
        return newPrice;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
