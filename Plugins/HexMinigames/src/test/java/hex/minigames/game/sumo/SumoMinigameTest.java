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
            var game = new SumoMinigame(mock(Plugin.class));
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
            var attack = mock(EntityDamageByEntityEvent.class);
            when(attack.getEntity()).thenReturn(player); when(attack.getDamager()).thenReturn(attacker);
            when(attack.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            assertEquals(EventDecision.DENY,game.onDamage(context,attack));
            verify(player).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
            game.onMove(context,new PlayerMoveEvent(player,at.get(),new Location(world,438,39,349)));
            assertEquals(RoundPlayerState.RESPAWN_DELAY,state.get());
            verify(context).respawn(eq(player),any(Location.class),any(Runnable.class));
            assertEquals(1,game.finish(context,RoundEndReason.TIME_LIMIT).players().get(id).points());
            game.reset(context);
            for (var block : barriers.values()) { var original = block.getBlockData(); verify(block).setBlockData(original, false); }
        }
    }
}
