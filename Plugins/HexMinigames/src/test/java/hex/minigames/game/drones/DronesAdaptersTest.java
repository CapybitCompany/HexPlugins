package hex.minigames.game.drones;

import hex.minigames.game.common.BlockChangeTracker;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DronesAdaptersTest {
    @Test void stationBodyUsesOwnersSkinAndIsImmovableAndRemovedOnce() {
        try(var profiles=mockStatic(io.papermc.paper.datacomponent.item.ResolvableProfile.class)) {
            World world=mock(World.class); Player owner=mock(Player.class); Mannequin body=mock(Mannequin.class);
            var source=mock(com.destroystokyo.paper.profile.PlayerProfile.class);
            var resolved=mock(io.papermc.paper.datacomponent.item.ResolvableProfile.class);
            var parts=mock(com.destroystokyo.paper.SkinParts.class);
            when(owner.getPlayerProfile()).thenReturn(source); when(owner.getName()).thenReturn("Pilot");
            when(owner.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS)).thenReturn(parts);
            profiles.when(() -> io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(source)).thenReturn(resolved);
            Location station=new Location(world,-73.5,9,81.5,90,0);
            when(world.spawn(eq(station),eq(Mannequin.class),any(Consumer.class))).thenAnswer(call -> {
                Consumer<Mannequin> setup=call.getArgument(2); setup.accept(body); return body;
            });
            var visual=new DroneBody(owner,station);
            verify(body).setProfile(resolved); verify(body).setSkinParts(parts); verify(body).setImmovable(true);
            verify(body).setInvulnerable(true); verify(body).setPersistent(false); verify(body).setCollidable(false);
            visual.remove(); visual.remove(); verify(body).remove();
        }
    }
    @Test void boxLockSynchronizesSpeedAndJumpAndRestoresExactlyOnceWithoutTeleport() {
        Player player=mock(Player.class); when(player.getWalkSpeed()).thenReturn(.23f);
        AttributeInstance jump=mock(AttributeInstance.class);
        var modifier=mock(org.bukkit.attribute.AttributeModifier.class);
        var lock=new DroneMovementLock(player,jump,modifier);
        verify(player).setWalkSpeed(0); verify(jump).addTransientModifier(modifier);
        verify(player,never()).teleport(any(Location.class));
        lock.restore(); lock.restore();
        verify(player).setWalkSpeed(.23f); verify(jump).removeModifier(modifier);
    }
    @Test void smallSequenceInventoryHasLongerGreenAndEveryPuzzleItemHidesTooltip() {
        try(GuiFixture fixture=new GuiFixture()) {
            var config=new DronesConfig(DronesFixtures.definition()); assertEquals(32,config.showTicks);
            var sequence=new DronesPuzzles.Sequence(new Random(7),config.showTicks,config.gapTicks,0);
            var gui=fixture.open(sequence); assertEquals(27,gui.getInventory().getSize());
            assertTrue(sequence.base().stream().allMatch(slot -> slot>=0&&slot<27));
            assertEquals(sequence.base().getFirst(),sequence.green(31)); assertEquals(-1,sequence.green(32));
            for(ItemStack item:fixture.stacks.constructed()) verify(item.getItemMeta()).setHideTooltip(true);
            gui.close();
        }
    }
    @Test void wrongFarRightCableSocketPenalizesButDraggingAcrossEmptyBoardDoesNot() {
        try(GuiFixture fixture=new GuiFixture()) {
            var cables=new DronesPuzzles.Cables(new Random(1)); var gui=fixture.open(cables);
            fixture.click(gui,9,ClickType.LEFT,0); fixture.drag(gui,Set.of(10,11,12,22,23,24),1);
            assertFalse(cables.locked(1)); assertFalse(cables.connected(DronesPuzzles.Color.GREEN));
            int wrong=(cables.targets().indexOf(DronesPuzzles.Color.GREEN)+1)%4;
            fixture.drag(gui,Set.of(2,3,4,5,(wrong+1)*9+8),2);
            assertTrue(cables.locked(41)); assertFalse(cables.locked(42)); gui.close();
        }
    }
    @Test void restoresExactScaleFlightSpeedsVisibilityAndGamemode() {
        Player player=mock(Player.class); AttributeInstance scale=mock(AttributeInstance.class);
        when(scale.getBaseValue()).thenReturn(1.23); when(player.getFlySpeed()).thenReturn(.17f); when(player.getWalkSpeed()).thenReturn(.23f);
        when(player.getAllowFlight()).thenReturn(true); when(player.isFlying()).thenReturn(true); when(player.isInvisible()).thenReturn(true);
        when(player.isCollidable()).thenReturn(true); when(player.hasGravity()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        DronePlayerState saved=DronePlayerState.capture(player,scale); saved.restore(player,scale);
        verify(scale).setBaseValue(1.23); verify(player).setFlySpeed(.17f); verify(player).setWalkSpeed(.23f);
        verify(player).setAllowFlight(true); verify(player).setFlying(true); verify(player).setInvisible(true);
        verify(player).setCollidable(true); verify(player).setGravity(true); verify(player).setGameMode(GameMode.ADVENTURE);
    }
    @Test void ninePartDroneAndPrivateBeamsAreRemovedAndColorsAreIndependent() {
        try(var bukkit=mockStatic(Bukkit.class)) {
            World world=mock(World.class); Plugin plugin=mock(Plugin.class); Player one=mock(Player.class),two=mock(Player.class);
            when(one.getWorld()).thenReturn(world); when(two.getWorld()).thenReturn(world);
            when(one.isOnline()).thenReturn(true); when(two.isOnline()).thenReturn(true);
            when(one.isChunkSent(anyLong())).thenReturn(true); when(two.isChunkSent(anyLong())).thenReturn(true);
            when(world.getMaxHeight()).thenReturn(64);
            BlockData red=mock(BlockData.class),green=mock(BlockData.class);
            bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(mock(BlockData.class));
            bukkit.when(() -> Bukkit.createBlockData(Material.RED_STAINED_GLASS)).thenReturn(red);
            bukkit.when(() -> Bukkit.createBlockData(Material.GREEN_STAINED_GLASS)).thenReturn(green);
            for(Material material:List.of(Material.RED_STAINED_GLASS,Material.GREEN_STAINED_GLASS,Material.BEACON,Material.IRON_BLOCK,Material.AIR)) {
                BlockData data=material.createBlockData(); var state=mock(org.bukkit.block.BlockState.class);
                when(data.createBlockState()).thenReturn(state);
                when(state.copy(any(Location.class))).thenAnswer(call -> {
                    var copy=mock(org.bukkit.block.BlockState.class); when(copy.getBlockData()).thenReturn(data);
                    when(copy.getLocation()).thenReturn(call.getArgument(0)); return copy;
                });
            }
            Block actual=mock(Block.class); when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(actual);
            when(actual.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
            List<BlockDisplay> entities=new ArrayList<>();
            when(world.spawn(any(Location.class),eq(BlockDisplay.class),any(Consumer.class))).thenAnswer(call -> {
                BlockDisplay display=mock(BlockDisplay.class); entities.add(display); Consumer<BlockDisplay> setup=call.getArgument(2); setup.accept(display); return display;
            });
            var config=new DronesConfig(DronesFixtures.definition());
            var first=new CheckpointBeams(plugin,one,config); var second=new CheckpointBeams(plugin,two,config);
            assertTrue(entities.isEmpty());
            verify(one).sendBlockChanges(argThat(states -> states.stream().filter(s -> s.getBlockData()==red).count()==4));
            verify(two).sendBlockChanges(argThat(states -> states.stream().filter(s -> s.getBlockData()==red).count()==4));
            first.complete(0); verify(one).sendBlockChange(any(Location.class),eq(green)); verify(two,never()).sendBlockChange(any(),eq(green));
            first.refresh(); verify(one).sendBlockChanges(argThat(states -> states.stream().filter(s -> s.getBlockData()==green).count()==1));
            DroneVisual visual=new DroneVisual(new Location(world,0,10,0),.35); assertEquals(9,visual.size());
            first.remove(); first.remove(); second.remove(); visual.remove(); visual.remove();
            for(BlockDisplay entity:entities) verify(entity).remove();
            verify(actual,never()).setType(any(),anyBoolean()); verify(actual,never()).setBlockData(any(),anyBoolean());
        }
    }
    @Test void onlyAssignedGateOpensAndAllOriginalBlockDataRestoresOnce() {
        try(var bukkit=mockStatic(Bukkit.class)) {
            World world=mock(World.class); bukkit.when(() -> Bukkit.getWorld("Hex_Minigames")).thenReturn(world);
            Map<String,Block> blocks=new HashMap<>(); Map<Block,BlockData> originals=new HashMap<>();
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> blocks.computeIfAbsent(call.getArgument(0)+","+call.getArgument(1)+","+call.getArgument(2),key -> {
                Block block=mock(Block.class); BlockData data=mock(BlockData.class); when(data.clone()).thenReturn(data); when(block.getBlockData()).thenReturn(data); originals.put(block,data); return block;
            }));
            var config=new DronesConfig(DronesFixtures.definition()); var one=new BlockChangeTracker(config.world); var two=new BlockChangeTracker(config.world);
            one.snapshot(config.stations.get(0).gate()); two.snapshot(config.stations.get(1).gate());
            blocks.values().forEach(block -> verify(block,never()).setType(any(),anyBoolean()));
            one.fill(config.stations.getFirst().gate(),Material.AIR);
            verify(blocks.get("-73,9,81")).setType(Material.AIR,false); verify(blocks.get("-73,9,78"),never()).setType(any(),anyBoolean());
            one.restoreAll(); one.restoreAll(); two.restoreAll();
            originals.forEach((block,data) -> verify(block).setBlockData(data,false));
        }
    }
    @Test void cablesUseActualDragEventAndClickingTargetCannotComplete() {
        try(GuiFixture fixture=new GuiFixture()) {
            var cables=new DronesPuzzles.Cables(new Random(1)); var gui=fixture.open(cables);
            fixture.click(gui,9,ClickType.LEFT,0); // GREEN source: far-left column
            int row=cables.targets().indexOf(DronesPuzzles.Color.GREEN)+1;
            fixture.click(gui,row*9+8,ClickType.LEFT,0); assertFalse(cables.connected(DronesPuzzles.Color.GREEN));
            fixture.drag(gui,Set.of(1,2,12,22,32,42,51,row*9+8),1); assertTrue(cables.connected(DronesPuzzles.Color.GREEN));
            gui.close(); verify(fixture.owner,atLeastOnce()).setItemOnCursor(null);
        }
    }
    @Test void guiRejectsSwapsDropsCollectCloneOutsideAndBottomInventoryDrag() {
        try(GuiFixture fixture=new GuiFixture()) {
            var cores=new DronesPuzzles.Cores(new Random(5)); var gui=fixture.open(cores);
            for(ClickType type:List.of(ClickType.NUMBER_KEY,ClickType.SHIFT_LEFT,ClickType.SHIFT_RIGHT,ClickType.DOUBLE_CLICK,
                    ClickType.DROP,ClickType.CONTROL_DROP,ClickType.SWAP_OFFHAND,ClickType.MIDDLE,ClickType.WINDOW_BORDER_LEFT)) fixture.click(gui,29,type,0);
            assertNotNull(cores.at(5)); assertNull(cores.at(0));
            fixture.click(gui,29,ClickType.LEFT,0); assertNull(cores.at(5));
            fixture.click(gui,-999,ClickType.LEFT,0); fixture.click(gui,55,ClickType.LEFT,0); fixture.drag(gui,Set.of(11,55),0);
            assertNull(cores.at(0)); gui.close(); assertNotNull(cores.at(5)); // cursor core safely returned to source
        }
    }
    @Test void coreDragChangesArrangementAndEscDoesNotReroll() {
        try(GuiFixture fixture=new GuiFixture()) {
            var cores=new DronesPuzzles.Cores(new Random(5)); var order=cores.order(); var gui=fixture.open(cores);
            var source=cores.at(5); fixture.click(gui,29,ClickType.LEFT,0); fixture.drag(gui,Set.of(11),1);
            assertEquals(source,cores.at(0)); assertFalse(cores.done()); assertFalse(cores.locked(1));
            gui.close(); gui=fixture.open(cores); assertEquals(order,cores.order()); assertEquals(source,cores.at(0)); gui.close();
        }
    }
    private static final class GuiFixture implements AutoCloseable {
        final org.mockito.MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        final org.mockito.MockedConstruction<ItemStack> stacks;
        final Player owner=mock(Player.class);
        final InventoryView view=mock(InventoryView.class);
        final ArrayDeque<Runnable> actions=new ArrayDeque<>();
        GuiFixture() {
            when(owner.getUniqueId()).thenReturn(UUID.randomUUID()); when(owner.getOpenInventory()).thenReturn(view);
            stacks=mockConstruction(ItemStack.class,(item,context) -> { when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)); when(item.getType()).thenReturn((Material)context.arguments().getFirst()); });
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(net.kyori.adventure.text.Component.class))).thenAnswer(call -> {
                Inventory inventory=mock(Inventory.class); when(inventory.getSize()).thenReturn(call.getArgument(1)); when(view.getTopInventory()).thenReturn(inventory); return inventory;
            });
        }
        DroneGui open(DronesPuzzles.Puzzle puzzle) { DroneGui gui=new DroneGui(owner,puzzle,0,new DronesConfig(DronesFixtures.definition()),actions::add); gui.show(0); return gui; }
        void click(DroneGui gui,int slot,ClickType type,long tick) {
            InventoryClickEvent event=mock(InventoryClickEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(owner);
            when(event.getClick()).thenReturn(type); when(event.getRawSlot()).thenReturn(slot); gui.click(event,tick); verify(event).setCancelled(true); drain();
        }
        void drag(DroneGui gui,Set<Integer> slots,long tick) {
            InventoryDragEvent event=mock(InventoryDragEvent.class); when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(owner); when(event.getRawSlots()).thenReturn(slots);
            gui.drag(event,tick); verify(event).setCancelled(true); drain();
        }
        void drain() { while(!actions.isEmpty()) actions.remove().run(); }
        public void close() { stacks.close(); bukkit.close(); }
    }
}
