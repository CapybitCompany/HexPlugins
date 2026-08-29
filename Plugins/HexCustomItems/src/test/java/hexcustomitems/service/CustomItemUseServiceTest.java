package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.MessageAction;
import hexcustomitems.model.SoundAction;
import hexcustomitems.region.RegionQuery;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemUseServiceTest extends PluginTestBase {

    private HexCustomItemsConfig config;
    private CustomItemRegistryService registry;
    private CooldownService cooldowns;
    private final List<String> dispatched = new ArrayList<>();
    private RegionQuery regionQuery = location -> Optional.empty();
    private CustomItemUseService useService;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        items.put("jump_potion", TestConfig.selfPotionItem("jump_potion", Material.POTION, "jump_boost", 5, 0));
        items.put("locked", TestConfig.item("locked", Material.POTION, "hex.items.use.locked", 0, 0,
                List.of(new SoundAction("entity.generic.drink", 1f, 1f, false))));
        items.put("grief", TestConfig.commandItem("grief", Material.PAPER, CommandExecutorType.CONSOLE,
                List.of("say hi"), true));
        items.put("multi", TestConfig.item("multi", Material.PAPER, null, 0, 0, List.of(
                new CommandAction(CommandExecutorType.CONSOLE, List.of("eco give %player% 5"), false),
                new MessageAction("<green>Dziękujemy", false),
                new SoundAction("entity.generic.drink", 1f, 1f, false))));
        items.put("noop", TestConfig.item("noop", Material.STONE, null, 0, 0, List.of()));
        config = TestConfig.withItems(items);

        registry = new CustomItemRegistryService(plugin, config);
        cooldowns = new CooldownService(() -> 1_000_000L);
        MessageService messageService = new MessageService(() -> config);
        UsePolicyService policy = new UsePolicyService(() -> config, location -> regionQuery.offensiveAllowed(location));
        ActionExecutor executor = new ActionExecutor(plugin, messageService, (sender, command) -> dispatched.add(command));
        useService = new CustomItemUseService(registry, cooldowns, policy, executor, messageService);

        player = server.addPlayer();
    }

    private ItemStack giveToHand(String itemId) {
        ItemStack stack = registry.createItem(registry.findById(itemId), 1);
        player.getInventory().setItemInMainHand(stack);
        return stack;
    }

    private ItemStack hand() {
        return player.getInventory().getItemInMainHand();
    }

    @Test
    void selfPotionAppliesEffectAndConsumes() {
        ItemStack stack = giveToHand("jump_potion");
        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertFalse(player.getActivePotionEffects().isEmpty(), "Effekt sollte gesetzt sein");
        assertTrue(hand().getType().isAir(), "Item sollte verbraucht sein");
    }

    @Test
    void actionsRunInOrder() {
        ItemStack stack = giveToHand("multi");
        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertEquals(List.of("eco give " + player.getName() + " 5"), dispatched);
        assertTrue(player.getHeardSounds().stream()
                .anyMatch(audio -> audio.getSound().equals("entity.generic.drink")), "SOUND-Aktion sollte laufen");
        assertEquals("Dziękujemy", TestConfig.plain(player.nextComponentMessage()), "MESSAGE-Aktion sollte laufen");
    }

    @Test
    void cooldownBlocksReuseWithoutConsuming() {
        cooldowns.apply(player.getUniqueId(), "jump_potion", 5);
        ItemStack stack = giveToHand("jump_potion");

        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertEquals(Material.POTION, hand().getType(), "Item darf bei aktivem Cooldown nicht verbraucht werden");
        assertTrue(player.getActivePotionEffects().isEmpty(), "Kein Effekt bei aktivem Cooldown");
    }

    @Test
    void permissionBlocksWithoutConsuming() {
        ItemStack stack = giveToHand("locked");
        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertEquals(Material.POTION, hand().getType(), "Ohne Permission kein Verbrauch");
    }

    @Test
    void offensiveActionBlockedWhenPolicyDenies() {
        player.getWorld().setPVP(false); // respect-pvp -> offensive verboten
        ItemStack stack = giveToHand("grief");

        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertEquals(Material.PAPER, hand().getType(), "Offensive Aktion darf hier nicht verbrauchen");
        assertTrue(dispatched.isEmpty(), "Offensive Command darf nicht laufen");
    }

    @Test
    void offensiveActionAllowedWhenPvpOn() {
        player.getWorld().setPVP(true);
        ItemStack stack = giveToHand("grief");

        useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertEquals(List.of("say hi"), dispatched);
        assertTrue(hand().getType().isAir());
    }

    @Test
    void emptyActionsNeitherConsumeNorSetCooldown() {
        ItemStack stack = giveToHand("noop");
        boolean handled = useService.tryUseItem(player, EquipmentSlot.HAND, stack);

        assertFalse(handled, "Item ohne Aktionen ist nicht nutzbar -> Rechtsklick darf nicht blockiert werden");
        assertEquals(Material.STONE, hand().getType(), "Leere Actions dürfen nicht verbrauchen");
        assertEquals(0L, cooldowns.remainingSeconds(player.getUniqueId(), "noop"));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void unmanagedItemReturnsFalse() {
        ItemStack plain = new ItemStack(Material.DIRT);
        assertFalse(useService.tryUseItem(player, EquipmentSlot.HAND, plain));
    }

    @Test
    void staleePdcItemIsNotHandledAndNotConsumed() {
        // Item mit PDC-ID, die (nach Reload/Config-Änderung) nicht mehr in der Registry existiert.
        CustomItemDefinition removed = TestConfig.commandItem("removed_item", Material.PAPER,
                CommandExecutorType.CONSOLE, List.of("say x"), false);
        ItemStack stale = registry.createItem(removed, 1); // trägt PDC "removed_item", ist aber nicht registriert
        player.getInventory().setItemInMainHand(stale);

        boolean handled = useService.tryUseItem(player, EquipmentSlot.HAND, stale);

        assertFalse(handled, "Stale-PDC-Item darf nicht verarbeitet werden (kein Cancel)");
        assertEquals(Material.PAPER, hand().getType(), "Stale-Item darf nicht verbraucht werden");
        assertTrue(dispatched.isEmpty());
    }
}
