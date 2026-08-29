package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.SpecialAction;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialItemActionServiceTest extends PluginTestBase {

    private SpecialItemActionService service;
    private CustomItemDefinition goldenHeart;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        goldenHeart = new CustomItemDefinition("golden_heart", "hex:golden_heart", 10003,
                Material.YELLOW_DYE, "<white>Golden Heart", List.of(), true, false, false,
                null, 0, 1, 0, List.of());
        HexCustomItemsConfig config = TestConfig.withItems(Map.of("golden_heart", goldenHeart));
        CustomItemRegistryService registry = new CustomItemRegistryService(plugin, config);
        MessageService messages = new MessageService(() -> config);
        service = new SpecialItemActionService(
                plugin,
                registry,
                new PlayerDataService(plugin),
                new CombatIntegrationService(plugin),
                messages
        );
        player = server.addPlayer();
    }

    @Test
    void goldenHeartAddsTemporaryAbsorptionUpToConfiguredLimit() {
        SpecialAction action = new SpecialAction("GOLDEN_HEART",
                Map.of("hearts", "6", "max", "10", "duration-seconds", "30"), false);

        assertTrue(service.execute(player, EquipmentSlot.HAND, goldenHeart, action));
        assertEquals(12.0D, player.getAbsorptionAmount(), 0.001D);

        assertTrue(service.execute(player, EquipmentSlot.HAND, goldenHeart, action));
        assertEquals(20.0D, player.getAbsorptionAmount(), 0.001D);

        assertFalse(service.execute(player, EquipmentSlot.HAND, goldenHeart, action));
        assertEquals(20.0D, player.getAbsorptionAmount(), 0.001D);
    }

    @Test
    void goldenHeartUsesCurrentShieldInsteadOfPersistentRedHeartCounter() {
        player.setAbsorptionAmount(16.0D);
        SpecialAction action = new SpecialAction("GOLDEN_HEART",
                Map.of("hearts", "1", "max", "10", "duration-seconds", "30"), false);

        assertTrue(service.execute(player, EquipmentSlot.HAND, goldenHeart, action));

        assertEquals(18.0D, player.getAbsorptionAmount(), 0.001D);
    }

    @Test
    void goldenHeartAbsorptionExpiresAfterConfiguredDuration() {
        SpecialAction action = new SpecialAction("GOLDEN_HEART",
                Map.of("hearts", "2", "max", "10", "duration-seconds", "1"), false);

        assertTrue(service.execute(player, EquipmentSlot.HAND, goldenHeart, action));
        assertEquals(4.0D, player.getAbsorptionAmount(), 0.001D);

        server.getScheduler().performTicks(20L);

        assertEquals(0.0D, player.getAbsorptionAmount(), 0.001D);
    }
}
