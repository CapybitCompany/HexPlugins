package hex.minigames.game.sumo;
import hex.minigames.game.*;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

final class SumoMinigameTest {
    @Test void arenaCombatWorksEvenWhileLaunchFlightIsStillTracked() throws Exception {
        var game = new SumoMinigame(mock(Plugin.class));
        var started = SumoMinigame.class.getDeclaredField("started"); started.setAccessible(true); started.set(game,true);
        Player victim = mock(Player.class), attacker = mock(Player.class); World world = mock(World.class);
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        when(victim.getUniqueId()).thenReturn(id); when(attacker.getUniqueId()).thenReturn(other);
        when(victim.getLocation()).thenReturn(new Location(world,448,49,329));
        RoundContext context = mock(RoundContext.class);
        when(context.state(any())).thenReturn(RoundPlayerState.ACTIVE); when(context.participants()).thenReturn(Set.of(id,other));
        game.onMove(context,new PlayerMoveEvent(victim,victim.getLocation(),victim.getLocation()));
        when(victim.getLocation()).thenReturn(new Location(world,438.5,48,349.5));
        when(attacker.getLocation()).thenReturn(new Location(world,438.5,48,348));
        var hit = mock(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class);
        when(hit.getAttacked()).thenReturn(victim); when(hit.getPlayer()).thenReturn(attacker);

        game.onPreAttack(context,hit);
        verify(victim).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
        clearInvocations(victim);
        // Landing height and the old scoring disc must not gate combat; repeated hits have no cooldown.
        when(victim.getLocation()).thenReturn(new Location(world,448,53,329));
        when(attacker.getLocation()).thenReturn(new Location(world,448,52,327));
        game.onPreAttack(context,hit);
        game.onPreAttack(context,hit);
        verify(victim,times(2)).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
        when(context.state(id)).thenReturn(RoundPlayerState.GHOST);
        game.onPreAttack(context,hit);
        verify(victim,times(2)).setVelocity(any());
    }

    @Test void launchesToCentreCountsArenaTimeAndRespawnsWithoutLosingPoints() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            Map<String, org.bukkit.block.Block> barriers = new HashMap<>();
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> barriers.computeIfAbsent(
                    call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2), ignored -> {
                        var block = mock(org.bukkit.block.Block.class);
                        when(block.getBlockData()).thenReturn(mock(org.bukkit.block.data.BlockData.class));
                        return block;
                    }));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBossBar(anyString(),any(BarColor.class),any(BarStyle.class))).thenReturn(mock(BossBar.class));
            Player player = mock(Player.class), attacker = mock(Player.class);
            UUID id = UUID.randomUUID(), other = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id); when(attacker.getUniqueId()).thenReturn(other);
            when(player.getWorld()).thenReturn(world);
            when(player.getInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
            AtomicReference<Location> at = new AtomicReference<>(new Location(world,448,49,324));
            when(player.getLocation()).thenAnswer(call -> at.get().clone());
            when(player.teleport(any(Location.class))).thenAnswer(call -> { at.set(call.getArgument(0)); return true; });
            RoundContext context = mock(RoundContext.class);
            var definition = new MinigameDefinition("monkey_run","SUMO",true,true,false,1,14,1,
                    Optional.of(new CuboidRegion("world",new BlockPosition(356,-33,265),new BlockPosition(547,143,429))),
                    List.of(),Optional.empty(),90,Map.of(),"test");
            when(context.definition()).thenReturn(definition);
            when(context.onlineParticipants()).thenReturn(List.of(player));
            when(context.participants()).thenReturn(Set.of(id,other));
            AtomicReference<RoundPlayerState> state = new AtomicReference<>(RoundPlayerState.ACTIVE);
            when(context.state(id)).thenAnswer(call -> state.get());
            when(context.state(other)).thenReturn(RoundPlayerState.ACTIVE);
            doAnswer(call -> { state.set(call.getArgument(1)); return null; }).when(context).state(eq(id),any());
            AtomicLong tick = new AtomicLong(); when(context.elapsedTicks()).thenAnswer(call -> tick.get());
            Plugin plugin = mock(Plugin.class);
            when(plugin.getName()).thenReturn("HexMinigames");
            when(plugin.namespace()).thenReturn("hexminigames");
            var game = new SumoMinigame(plugin);
            game.prepare(context);
            assertEquals(20, barriers.size());
            assertTrue(barriers.containsKey("431:48:370"));
            assertTrue(barriers.containsKey("441:48:370"));
            assertTrue(barriers.containsKey("443:50:329"));
            assertTrue(barriers.containsKey("451:50:329"));
            for (var block : barriers.values()) { verify(block).setType(Material.BARRIER, false); verify(block, never()).setType(Material.AIR, false); }
            game.start(context);
            for (var block : barriers.values()) verify(block).setType(Material.AIR, false);
            at.set(new Location(world,448,49,329));
            game.onMove(context,new PlayerMoveEvent(player,at.get(),at.get()));
            for (int i=0;i<40;i++) { tick.set(i); game.handleTick(context); }
            verify(player, times(1)).teleport(any(Location.class)); // Preparation only, never guided landing.
            verify(player, times(1)).setVelocity(any(org.bukkit.util.Vector.class));
            at.set(new Location(world,438.5,48,349.5));
            for (int i=40;i<440;i++) { tick.set(i); game.handleTick(context); }
            when(attacker.getLocation()).thenReturn(new Location(world,438.5,48,348));
            var attack = mock(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class);
            when(attack.getAttacked()).thenReturn(player); when(attack.getPlayer()).thenReturn(attacker);

            assertEquals(EventDecision.DENY,game.onPreAttack(context,attack));
            verify(player).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
            game.onMove(context,new PlayerMoveEvent(player,at.get(),new Location(world,438,39,349)));
            assertEquals(RoundPlayerState.RESPAWN_DELAY,state.get());
            verify(context).respawn(eq(player),any(Location.class),any(Runnable.class));
            var arrival = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(context).respawn(eq(player),any(Location.class),arrival.capture());
            arrival.getValue().run();
            assertEquals(RoundPlayerState.ACTIVE,state.get());
            at.set(new Location(world,448,49,324));
            when(attacker.getLocation()).thenReturn(new Location(world,448,49,323));
            clearInvocations(player);
            game.onPreAttack(context,attack);
            game.onPreAttack(context,attack);
            verify(player,times(2)).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
            assertEquals(1,game.finish(context,RoundEndReason.TIME_LIMIT).players().get(id).points());
            game.reset(context);
            for (var block : barriers.values()) { var original = block.getBlockData(); verify(block).setBlockData(original, false); }
        }
    }
}
