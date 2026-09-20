package hex.minigames.game.tag;

import hex.minigames.game.*;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class TagMinigameTest {
    @Test
    void rolesAndGlowRestoreAfterTransfersAndRoundEnd() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var manager = mock(ScoreboardManager.class);
            var board = mock(Scoreboard.class);
            var red = mock(Team.class);
            var green = mock(Team.class);
            bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
            when(manager.getNewScoreboard()).thenReturn(board);
            when(board.registerNewTeam("hex_tag_red")).thenReturn(red);
            when(board.registerNewTeam("hex_tag_green")).thenReturn(green);
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class))).thenReturn(mock(BossBar.class));
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            List<Player> players = new ArrayList<>();
            Map<UUID, Scoreboard> oldBoards = new HashMap<>();
            for (int index = 0; index < 4; index++) {
                Player player = mock(Player.class);
                when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                when(player.getName()).thenReturn("Player" + index);
                when(player.getLocation()).thenReturn(new Location(world, 357, -30, -118));
                when(player.isOnline()).thenReturn(true);
                Scoreboard oldBoard = mock(Scoreboard.class);
                when(player.getScoreboard()).thenReturn(oldBoard);
                oldBoards.put(player.getUniqueId(), oldBoard);
                players.add(player);
            }
            when(players.get(0).isGlowing()).thenReturn(true);
            var context = mock(RoundContext.class);
            var ids = new LinkedHashSet<>(players.stream().map(Player::getUniqueId).toList());
            when(context.participants()).thenReturn(ids);
            when(context.onlineParticipants()).thenReturn(players);
            when(context.state(any())).thenReturn(RoundPlayerState.ACTIVE);
            when(context.definition()).thenReturn(new MinigameDefinition("tag", "Tag", true, true, false, 4, 14, 1,
                    Optional.of(new CuboidRegion("world", new BlockPosition(327, -34, -90), new BlockPosition(378, -8, -141))),
                    List.of(new LocationSpec(357, -30, -118, 0, 0, true)), Optional.empty(), 150, Map.of(), "test"));
            var game = new TagMinigame(mock(Plugin.class));
            game.prepare(context);
            game.start(context);
            var field = TagMinigame.class.getDeclaredField("runtime");
            field.setAccessible(true);
            TagRuntime runtime = (TagRuntime) field.get(game);
            Player attacker = players.stream().filter(p -> runtime.isTagger(p.getUniqueId())).findFirst().orElseThrow();
            Player victim = players.stream().filter(p -> !runtime.isTagger(p.getUniqueId())).findFirst().orElseThrow();
            var hit = mock(EntityDamageByEntityEvent.class);
            when(hit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            when(hit.getDamager()).thenReturn(attacker);
            when(hit.getEntity()).thenReturn(victim);
            when(context.elapsedTicks()).thenReturn(100L);
            assertEquals(EventDecision.DENY, game.onDamage(context, hit));
            assertTrue(runtime.isTagger(victim.getUniqueId()));
            assertFalse(runtime.isTagger(attacker.getUniqueId()));
            verify(red).addEntry(victim.getName());
            verify(victim).sendTitle(eq(""), contains("Berek!"), eq(0), eq(20), eq(0));
            verify(victim).playSound(any(Location.class), eq("minecraft:entity.villager.no"), eq(1.0f), eq(0.7f));
            when(hit.getDamager()).thenReturn(victim);
            when(hit.getEntity()).thenReturn(attacker);
            when(context.elapsedTicks()).thenReturn(139L);
            game.onDamage(context, hit);
            assertTrue(runtime.isTagger(victim.getUniqueId()));
            when(context.elapsedTicks()).thenReturn(140L);
            game.onDamage(context, hit);
            assertTrue(runtime.isTagger(attacker.getUniqueId()));
            assertEquals(EventDecision.DENY, game.onDamage(context, mock(EntityDamageEvent.class)));
            when(context.elapsedTicks()).thenReturn(3000L);
            game.finish(context, RoundEndReason.TIME_LIMIT);
            game.reset(context);
            verify(red).unregister();
            verify(green).unregister();
            for (int i = 0; i < players.size(); i++) {
                Player player = players.get(i);
                Scoreboard originalBoard = oldBoards.get(player.getUniqueId());
                verify(player).setScoreboard(originalBoard);
                if (i == 0) verify(player, times(2)).setGlowing(true);
                else verify(player).setGlowing(false);
            }
        }
    }
}
