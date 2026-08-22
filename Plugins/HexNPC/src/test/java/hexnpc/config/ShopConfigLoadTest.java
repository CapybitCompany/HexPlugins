package hexnpc.config;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ładowanie nowych ścieżek konfiguracji sklepu: potwierdzenie, audyt, failover
 * czatu (z ograniczeniem do timeoutu), walidacja slotów i nazwy tabeli oraz
 * brak usuniętego klucza wiadomości.
 */
class ShopConfigLoadTest {

    private final HexNpcConfigLoader loader = new HexNpcConfigLoader();

    private ShopConfig load(YamlConfiguration yaml) {
        return loader.load(yaml).shops();
    }

    @Test
    void loadsConfirmationAuditAndFailoverPaths() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("shops.sign-editor.timeout-seconds", 20);
        yaml.set("shops.sign-editor.chat-fallback-seconds", 6);
        yaml.set("shops.confirmation.enabled", false);
        yaml.set("shops.confirmation.threshold", 10);
        yaml.set("shops.audit-log.enabled", false);
        yaml.set("shops.audit-log.table", "my_audit");
        yaml.set("shops.audit-log.log-denied-transactions", false);

        ShopConfig cfg = load(yaml);
        assertEquals(20, cfg.signTimeoutSeconds());
        assertEquals(6, cfg.signFailoverSeconds());
        assertFalse(cfg.confirmation().enabled());
        assertEquals(10, cfg.confirmation().threshold());
        assertFalse(cfg.auditLog().enabled());
        assertEquals("my_audit", cfg.auditLog().table());
        assertFalse(cfg.auditLog().logDenied());
    }

    @Test
    void chatFallbackIsClampedToTimeout() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("shops.sign-editor.timeout-seconds", 5);
        yaml.set("shops.sign-editor.chat-fallback-seconds", 10); // > timeout
        ShopConfig cfg = load(yaml);
        assertTrue(cfg.signFailoverSeconds() <= cfg.signTimeoutSeconds(),
                "failover czatu musi być <= timeout");
    }

    @Test
    void confirmationSlotsAreSanitizedDistinct() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("shops.confirmation.size", 27);
        yaml.set("shops.confirmation.preview-slot", 5);
        yaml.set("shops.confirmation.confirm-slot", 5);
        yaml.set("shops.confirmation.cancel-slot", 5);
        ShopConfig.Confirmation c = load(yaml).confirmation();
        assertNotEquals(c.previewSlot(), c.confirmSlot());
        assertNotEquals(c.confirmSlot(), c.cancelSlot());
        assertNotEquals(c.previewSlot(), c.cancelSlot());
        assertTrue(c.previewSlot() >= 0 && c.previewSlot() < c.size());
        assertTrue(c.confirmSlot() >= 0 && c.confirmSlot() < c.size());
        assertTrue(c.cancelSlot() >= 0 && c.cancelSlot() < c.size());
    }

    @Test
    void invalidAuditTableFallsBackToDefault() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("shops.audit-log.table", "bad name!");
        assertEquals("npc_shop_audit", load(yaml).auditLog().table());
    }

    @Test
    void removedSelectedPriceMessageIsGone() {
        assertFalse(ShopMessages.defaultValues().containsKey("gui-selected-price"),
                "usunięty, nieużywany klucz nie może wracać w domyślnych");
    }
}
