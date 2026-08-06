package hex.auctionbazaar.config;

/**
 * Konfiguracja połączenia z bazą. HexAuctionBazaar korzysta ze wspólnego
 * połączenia MySQL z HexCore (nie tworzy własnej puli, nie zna hosta/hasła).
 *
 * @param provider            dostawca połączenia; obecnie akceptowany wyłącznie "HEXCORE"
 * @param required            czy brak/niesprawna baza ma wyłączyć plugin przy starcie
 * @param healthCheckOnStartup czy przy starcie wykonać SELECT 1 przez HexCore
 */
public record DatabaseConfig(String provider, boolean required, boolean healthCheckOnStartup) {

    public static final String PROVIDER_HEXCORE = "HEXCORE";

    public boolean usesHexCore() {
        return PROVIDER_HEXCORE.equalsIgnoreCase(provider);
    }
}
