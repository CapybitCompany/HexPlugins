package hex.auctionbazaar.auction.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aukcja Domu Aukcyjnego.
 *
 * Snapshot podatkowy ({@code taxPercent}, {@code taxAmount}, {@code listingFeeAmount},
 * {@code economicNetAmount}) jest wyliczany JEDEN RAZ przy wystawieniu z faktycznie
 * rozwiązanego progu rangi i ceny, po czym jest niezmienny. Zmiana rangi gracza lub
 * przeładowanie konfiguracji NIE zmienia istniejących aukcji. Dla aukcji legacy
 * (sprzed wprowadzenia podatku z góry) pola snapshotu są {@code null} - traktujemy je
 * bezpiecznie jako podatek=0 / opłata=0 (patrz akcesory *OrZero / economicNetOrGross).
 */
public record AuctionListing(
        long id,
        UUID sellerUuid,
        String sellerName,
        byte[] itemBlob,
        String itemMaterial,
        int itemAmount,
        BigDecimal price,
        ListingState state,
        long createdAt,
        long expiresAt,
        UUID reservedByUuid,
        Long reservedUntil,
        UUID soldToUuid,
        Long soldAt,
        // Niezmienny snapshot podatkowy (null = aukcja legacy).
        BigDecimal taxPercent,
        BigDecimal taxAmount,
        BigDecimal listingFeeAmount,
        BigDecimal economicNetAmount
) {
    /** Czy aukcja ma zapisany snapshot podatkowy (nie jest legacy). */
    public boolean hasTaxSnapshot() {
        return taxAmount != null;
    }

    public BigDecimal taxPercentOrZero() {
        return taxPercent == null ? BigDecimal.ZERO : taxPercent;
    }

    public BigDecimal taxAmountOrZero() {
        return taxAmount == null ? BigDecimal.ZERO : taxAmount;
    }

    public BigDecimal listingFeeAmountOrZero() {
        return listingFeeAmount == null ? BigDecimal.ZERO : listingFeeAmount;
    }

    /** Wynik ekonomiczny sprzedawcy; legacy bez snapshotu -> pełny brutto (podatek=0). */
    public BigDecimal economicNetOrGross() {
        return economicNetAmount == null ? price : economicNetAmount;
    }
}
