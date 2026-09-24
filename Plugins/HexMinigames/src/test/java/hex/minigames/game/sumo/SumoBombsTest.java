package hex.minigames.game.sumo;

import hex.minigames.game.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class SumoBombsTest {
    @Test void fallingChargeCanBeCollectedThrownAndKnocksBackItsThrowerToo() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);
        try (var stacks = mockConstruction(ItemStack.class, (item, construction) -> {
            when(item.getItemMeta()).thenReturn(meta); when(item.hasItemMeta()).thenReturn(true); when(item.getAmount()).thenReturn(1);
        })) {
            Plugin plugin = mock(Plugin.class); when(plugin.namespace()).thenReturn("hexminigames");
            World world = mock(World.class);
            Player thrower = player(world, 438.5), other = player(world, 440);
            RoundContext context = mock(RoundContext.class);
            when(context.onlineParticipants()).thenReturn(List.of(thrower, other));
            when(context.state(any())).thenReturn(RoundPlayerState.ACTIVE);
            when(context.definition()).thenReturn(new MinigameDefinition("monkey_run","SUMO",true,true,false,1,14,1,
                    Optional.empty(),List.of(),Optional.empty(),90,
                    Map.of("bombs",Map.of("min-interval-seconds",1,"max-interval-seconds",1)),"test"));
            AtomicLong tick = new AtomicLong(); when(context.elapsedTicks()).thenAnswer(call -> tick.get());
            List<Item> entities = new ArrayList<>();
            when(world.dropItem(any(Location.class),any(ItemStack.class),any())).thenAnswer(call -> {
                Item item = mock(Item.class); entities.add(item);
                Location at = call.getArgument(0);
                when(item.isValid()).thenReturn(true); when(item.getWorld()).thenReturn(world);
                when(item.getLocation()).thenReturn(at); when(item.getY()).thenReturn(at.getY());
                Consumer<Item> setup = call.getArgument(2); setup.accept(item); return item;
            });
            SumoBombs bombs = new SumoBombs(plugin); bombs.start(context);
            tick.set(19); bombs.tick(context); assertTrue(entities.isEmpty());
            tick.set(20); bombs.tick(context); assertEquals(1,entities.size());
            Item pickup = entities.getFirst();
            assertEquals(55.8, pickup.getLocation().getY(), 0.001);
            verify(pickup).setCanPlayerPickup(false);
            verify(thrower).playSound(any(Location.class),eq("minecraft:entity.glow_squid.ambient"),eq(1f),eq(1f));
            Location arena = thrower.getLocation();
            when(pickup.getLocation()).thenReturn(arena); when(pickup.getY()).thenReturn(48.0);
            tick.set(21); bombs.tick(context);
            verify(thrower.getInventory()).addItem(any(ItemStack.class)); verify(pickup).remove();
            ItemStack held = new ItemStack(Material.FIRE_CHARGE);
            when(thrower.getInventory().getItem(EquipmentSlot.HAND)).thenReturn(held);
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(thrower); when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR); when(event.getItem()).thenReturn(held);
            assertTrue(bombs.interact(context,event));
            Item thrown = entities.get(1);
            when(thrown.isOnGround()).thenReturn(true);
            when(thrown.getLocation()).thenReturn(arena);
            tick.set(22); bombs.tick(context);
            verify(thrower).setVelocity(new Vector(1.8,0.55,0));
            verify(other).setVelocity(new Vector(1.8,0.55,0));
            verify(thrown).remove();
            verify(world,never()).createExplosion(any(Location.class),anyFloat());
            bombs.clear(context);
        }
    }

    private Player player(World world, double x) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getWorld()).thenReturn(world);
        when(player.getX()).thenReturn(x); when(player.getY()).thenReturn(48.0); when(player.getZ()).thenReturn(349.5);
        when(player.getLocation()).thenReturn(new Location(world,x,48,349.5));
        when(player.getEyeLocation()).thenReturn(new Location(world,x,49.6,349.5));
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        return player;
    }
}
