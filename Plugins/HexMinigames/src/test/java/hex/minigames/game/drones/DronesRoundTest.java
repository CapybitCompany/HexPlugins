package hex.minigames.game.drones;

import hex.minigames.game.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DronesRoundTest {
    @Test void initialBoxMovementDoesNotRewindClientAndClockIsVisibleDuringPuzzleAndPenalty() {
        try(Fixture f=new Fixture()) {
            f.game.prepare(f.context);
            PlayerMoveEvent move=mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(f.player);
            when(move.getTo()).thenReturn(new Location(f.world,-73.4,9,81.5));
            f.game.onMove(f.context,move); verify(move,never()).setTo(any());
            f.game.start(f.context); f.game.handleTick(f.context);
            verify(f.player).sendActionBar(hex.minigames.util.Text.component("&fCzas: &e05:00 &8| &fPunkty kontrolne: &e0/4"));
            f.launch(); f.clickBlock(-190,-12,37); f.nanos.set(61_000_000_000L); f.tick.set(20);
            var cables=(DronesPuzzles.Cables)f.currentGui.get().puzzle();
            cables.drag(cables.targets().get(0),Set.of(1),20); f.game.handleTick(f.context);
            verify(f.player).sendActionBar(hex.minigames.util.Text.component("&fCzas: &e03:59 &8| &cKara: 2 sekundy"));
            f.game.reset(f.context);
        }
    }
    @Test void paperInputDrivesCustomVelocityAndBoundaryReturnsToLastValidPosition() {
        try(Fixture f=new Fixture()) {
            f.game.prepare(f.context); f.game.start(f.context); f.launch();
            Input input=mock(Input.class); when(input.isForward()).thenReturn(true);
            PlayerInputEvent event=mock(PlayerInputEvent.class); when(event.getPlayer()).thenReturn(f.player); when(event.getInput()).thenReturn(input);
            f.game.onInput(f.context,event); f.tick.set(1); f.game.handleTick(f.context);
            verify(f.player).setVelocity(argThat(v -> v.getX()<-.04 && Math.abs(v.getY())<.001));
            PlayerMoveEvent move=mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(f.player);
            when(move.getTo()).thenReturn(new Location(f.world,-250,9,81));
            f.game.onMove(f.context,move);
            verify(move).setTo(argThat(at -> at.getX()==-76.5 && at.getY()==9 && at.getZ()==81.5));
            f.game.reset(f.context);
        }
    }
    @Test void launchIsLockedDuringTutorialThenOnlyOnceAndControllerCannotChangeSlots() {
        try(Fixture f=new Fixture()) {
            f.game.prepare(f.context); f.launch(); assertTrue(f.entities.isEmpty());
            verify(f.context).blockChat(f.player.getUniqueId());
            f.game.start(f.context); verify(f.inventory).setItem(eq(0),any()); f.launch(); assertEquals(9,f.entities.size());
            assertEquals(1,f.bodies.constructed().size()); verify(f.locks.constructed().getFirst()).restore();
            f.launch(); assertEquals(9,f.entities.size()); assertEquals(1,f.bodies.constructed().size());
            assertEquals(EventDecision.DENY,f.game.onHeldSlot(f.context,mock(PlayerItemHeldEvent.class)));
            assertEquals(EventDecision.DENY,f.game.onDropItem(f.context,mock(PlayerDropItemEvent.class)));
            assertEquals(EventDecision.DENY,f.game.onToggleFlight(f.context,mock(PlayerToggleFlightEvent.class)));
            f.game.reset(f.context); verify(f.saved).restore(f.player); f.entities.forEach(entity -> verify(entity).remove());
        }
    }
    @Test void fourCheckpointsReturnToOwnStationOpenOnlyOwnGateThenEitherGeneratorFinishesOnce() throws Exception {
        try(Fixture f=new Fixture()) {
            f.game.prepare(f.context); f.game.start(f.context);
            f.clickBlock(-223,11,70); verify(f.player,never()).openInventory(any(Inventory.class));
            f.launch(); Location spawn=f.teleported.get().clone(); assertEquals(-76.5,spawn.getX());
            var config=new DronesConfig(DronesFixtures.definition());
            for(int cp:new int[]{2,0,3,1}) {
                var at=config.checkpoints.get(cp).block(); f.clickBlock(at.x(),at.y(),at.z());
                DroneGui gui=f.currentGui.get(); assertNotNull(gui); f.tick.set(DronesFixtures.solve(gui.puzzle(),f.tick.get()));
                f.game.handleTick(f.context);
            }
            verify(f.saved).restore(f.player); assertEquals(-73.5,f.teleported.get().getX()); assertEquals(81.5,f.teleported.get().getZ());
            verify((org.bukkit.block.data.Openable)f.blocks.get("-73,9,81").getBlockData()).setOpen(true);
            verify(f.blocks.get("-73,9,81"),never()).setType(any(),anyBoolean());
            assertFalse(f.blocks.containsKey("-73,9,78")); f.entities.forEach(entity -> verify(entity).remove());
            verify(f.player).sendActionBar(hex.minigames.util.Text.component("&cIDŹ DO GENERATORA!"));
            f.clickBlock(-223,11,70); var puzzle=f.currentGui.get().puzzle(); assertInstanceOf(DronesPuzzles.Generator.class,puzzle);
            f.game.onInventoryClose(f.context,f.closeEvent());
            f.clickBlock(-65,11,72); assertSame(puzzle,f.currentGui.get().puzzle());
            f.tick.set(DronesFixtures.solve(puzzle,f.tick.get())); f.nanos.set(102_381_000_000L); f.game.handleTick(f.context);
            verify(f.context).state(f.player.getUniqueId(),RoundPlayerState.FINISHED);
            PlayerMoveEvent move=mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(f.player);
            when(move.getTo()).thenReturn(new Location(f.world,-80,9,80)); f.game.onMove(f.context,move);
            verify(move,never()).setTo(any());
            f.clickBlock(-65,11,72); f.game.handleTick(f.context);
            verify(f.context,times(1)).state(f.player.getUniqueId(),RoundPlayerState.FINISHED);
            var result=f.game.finish(f.context,RoundEndReason.MINIGAME_REQUEST); assertEquals(4,result.players().get(f.player.getUniqueId()).points());
            f.game.reset(f.context); f.game.reset(f.context); verify(f.blocks.get("-73,9,81"),times(2)).setBlockData(any(),eq(false));
            verify(f.bodies.constructed().getFirst()).remove();
        }
    }
    @Test void timeoutClosesGuiRestoresControllerAndRepeatedCleanupIsSafe() {
        try(Fixture f=new Fixture()) {
            f.game.prepare(f.context); f.game.start(f.context); f.launch(); f.clickBlock(-190,-12,37);
            f.nanos.set(299_999_999_999L); f.game.handleTick(f.context); verify(f.context,never()).requestFinish(RoundEndReason.TIME_LIMIT);
            f.nanos.incrementAndGet(); f.game.handleTick(f.context); verify(f.context).requestFinish(RoundEndReason.TIME_LIMIT);
            var result=f.game.finish(f.context,RoundEndReason.TIME_LIMIT); f.game.reset(f.context); f.game.reset(f.context);
            assertTrue(result.players().get(f.player.getUniqueId()).failed()); assertEquals(0,result.players().get(f.player.getUniqueId()).points());
            verify(f.player).closeInventory(); verify(f.saved).restore(f.player); verify(f.player,atLeastOnce()).setItemOnCursor(null);
            f.entities.forEach(entity -> verify(entity).remove());
        }
    }
    @Test void disconnectAndStopRemoveDisplaysAndRestoreWhileOnePlayerCanContinue() {
        try(Fixture f=new Fixture()) {
            assertTrue(f.game.allowSingleRemainingPlayer()); f.game.prepare(f.context); f.game.start(f.context); f.launch();
            f.game.handlePlayerQuit(f.context,f.player.getUniqueId()); f.game.reset(f.context);
            verify(f.saved).restore(f.player); f.entities.forEach(entity -> verify(entity).remove());
        }
    }
    private static class Fixture implements AutoCloseable {
        final org.mockito.MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        final org.mockito.MockedStatic<DronePlayerState> states=mockStatic(DronePlayerState.class);
        final org.mockito.MockedConstruction<DroneMovementLock> locks=mockConstruction(DroneMovementLock.class);
        final org.mockito.MockedConstruction<DroneBody> bodies=mockConstruction(DroneBody.class);
        final org.mockito.MockedConstruction<ItemStack> stacks;
        final World world=mock(World.class); final Player player=mock(Player.class);
        final PlayerInventory inventory=mock(PlayerInventory.class); final InventoryView view=mock(InventoryView.class);
        final Plugin plugin=mock(Plugin.class); final RoundContext context=mock(RoundContext.class);
        final AtomicLong nanos=new AtomicLong(),tick=new AtomicLong();
        final AtomicReference<Location> teleported=new AtomicReference<>();
        final AtomicReference<DroneGui> currentGui=new AtomicReference<>();
        final DronePlayerState saved=mock(DronePlayerState.class);
        final List<BlockDisplay> entities=new ArrayList<>(); final Map<String,Block> blocks=new HashMap<>();
        final DronesMinigame game;
        final AtomicReference<RoundPlayerState> state=new AtomicReference<>(RoundPlayerState.ACTIVE);
        Fixture() {
            when(plugin.namespace()).thenReturn("hexminigames"); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(world.getName()).thenReturn("Hex_Minigames"); when(player.getWorld()).thenReturn(world); when(player.getUniqueId()).thenReturn(new UUID(0,1));
            when(player.getName()).thenReturn("Pilot"); when(player.isOnline()).thenReturn(true); when(player.getInventory()).thenReturn(inventory); when(player.getOpenInventory()).thenReturn(view);
            teleported.set(new Location(world,-73.5,9,81.5)); when(player.getLocation()).thenAnswer(c -> teleported.get().clone());
            when(player.teleport(any(Location.class))).thenAnswer(c -> { teleported.set(((Location)c.getArgument(0)).clone()); return true; });
            states.when(() -> DronePlayerState.capture(player)).thenReturn(saved); states.when(() -> DronePlayerState.reach(player)).thenReturn(4.5);
            bukkit.when(() -> Bukkit.getWorld("Hex_Minigames")).thenReturn(world); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            bukkit.when(() -> Bukkit.createBossBar(anyString(),any(),any())).thenReturn(mock(BossBar.class));
            bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(mock(BlockData.class));
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(c -> blocks.computeIfAbsent(c.getArgument(0)+","+c.getArgument(1)+","+c.getArgument(2),k -> {
                Block block=mock(Block.class); BlockData data=mock(org.bukkit.block.data.Openable.class); when(data.clone()).thenReturn(data); when(block.getBlockData()).thenReturn(data); return block;
            }));
            when(world.spawn(any(Location.class),eq(BlockDisplay.class),any(Consumer.class))).thenAnswer(c -> {
                BlockDisplay display=mock(BlockDisplay.class); entities.add(display); Consumer<BlockDisplay> setup=c.getArgument(2); setup.accept(display); return display;
            });
            when(context.definition()).thenReturn(DronesFixtures.definition()); when(context.onlineParticipants()).thenReturn(List.of(player)); doReturn(Set.of(player.getUniqueId())).when(context).participants();
            when(context.elapsedTicks()).thenAnswer(c -> tick.get()); when(context.state(any())).thenAnswer(c -> state.get());
            doAnswer(c -> { state.set(c.getArgument(1)); return null; }).when(context).state(any(),any());
            ItemMeta meta=mock(ItemMeta.class); PersistentDataContainer data=mock(PersistentDataContainer.class); when(meta.getPersistentDataContainer()).thenReturn(data); when(data.has(any(),any())).thenReturn(true);
            stacks=mockConstruction(ItemStack.class,(item,c) -> { when(item.getItemMeta()).thenReturn(meta); when(item.hasItemMeta()).thenReturn(true); });
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(net.kyori.adventure.text.Component.class))).thenAnswer(c -> {
                Inventory inv=mock(Inventory.class); when(inv.getSize()).thenReturn(c.getArgument(1)); currentGui.set(c.getArgument(0)); when(view.getTopInventory()).thenReturn(inv); return inv;
            });
            doAnswer(c -> { when(view.getTopInventory()).thenReturn(null); return null; }).when(player).closeInventory();
            game=new DronesMinigame(plugin,nanos::get);
        }
        void launch() {
            PlayerInteractEvent event=mock(PlayerInteractEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND); ItemStack item=new ItemStack(Material.RECOVERY_COMPASS); when(event.getItem()).thenReturn(item); game.onInteract(context,event);
        }
        void clickBlock(int x,int y,int z) {
            Block block=mock(Block.class); when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
            RayTraceResult hit=mock(RayTraceResult.class); when(hit.getHitBlock()).thenReturn(block); when(player.rayTraceBlocks(4.5,FluidCollisionMode.NEVER)).thenReturn(hit);
            PlayerInteractEvent event=mock(PlayerInteractEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND); when(event.getClickedBlock()).thenReturn(block); game.onInteract(context,event);
        }
        org.bukkit.event.inventory.InventoryCloseEvent closeEvent() {
            var event=mock(org.bukkit.event.inventory.InventoryCloseEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getInventory()).thenReturn(currentGui.get().getInventory()); return event;
        }
        public void close() { stacks.close(); bodies.close(); locks.close(); states.close(); bukkit.close(); }
    }
}
