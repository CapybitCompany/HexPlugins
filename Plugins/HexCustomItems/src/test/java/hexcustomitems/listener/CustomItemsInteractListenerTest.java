package hexcustomitems.listener;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.ActionExecutor;
import hexcustomitems.service.CooldownService;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.CustomItemUseService;
import hexcustomitems.service.MessageService;
import hexcustomitems.service.UsePolicyService;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CustomItemsInteractListenerTest extends PluginTestBase {

    private CustomItemRegistryService registry;
    private CustomItemsInteractListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        Map<String, CustomItemDefinition> items = Map.of(
                "jump_potion", TestConfig.selfPotionItem("jump_potion", Material.POTION, "jump_boost", 0, 0));
        HexCustomItemsConfig config = TestConfig.withItems(items);
        registry = new CustomItemRegistryService(plugin, config);
        MessageService messages = new MessageService(() -> config);
        CooldownService cooldowns = new CooldownService();
        UsePolicyService policy = new UsePolicyService(() -> config, location -> Optional.empty());
        ActionExecutor executor = new ActionExecutor(plugin, messages);
        CustomItemUseService useService = new CustomItemUseService(registry, cooldowns, policy, executor, messages);
        listener = new CustomItemsInteractListener(useService);
        player = server.addPlayer();
    }

    @SuppressWarnings("deprecation") // PlayerInteractEvent-Konstruktor ist für Test-Zwecke ausreichend.
    private PlayerInteractEvent rightClick(ItemStack item) {
        player.getInventory().setItemInMainHand(item);
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    // Hinweis: isCancelled() ist bei RIGHT_CLICK_AIR per Default true (kein Block).
    // Ob DIESER Listener abgebrochen hat, zeigt useItemInHand() == DENY.

    @Test
    void validManagedItemCancelsEvent() {
        ItemStack valid = registry.createItem(registry.findById("jump_potion"), 1);
        PlayerInteractEvent event = rightClick(valid);

        listener.onInteract(event);

        assertEquals(Event.Result.DENY, event.useItemInHand(),
                "Nutzbares Custom-Item sollte den Rechtsklick abbrechen");
    }

    @Test
    void staleePdcItemDoesNotCancelEvent() {
        // Item trägt eine PDC-ID, die nicht (mehr) in der Registry existiert.
        CustomItemDefinition removed = TestConfig.commandItem("removed_item", Material.PAPER,
                CommandExecutorType.CONSOLE, List.of("say x"), false);
        ItemStack stale = registry.createItem(removed, 1);
        PlayerInteractEvent event = rightClick(stale);

        listener.onInteract(event);

        assertNotEquals(Event.Result.DENY, event.useItemInHand(),
                "Stale-PDC-Item darf den Rechtsklick nicht blockieren");
    }
}
