package hex.minigames.game.tag;
import hex.minigames.game.*;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class TagMinigameTest {
    @Test void transferChangesPacketRolesAndAppliesStrongerKnockbackWithoutBypassingImmunity() throws Exception {
        try (var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBossBar(anyString(),any(BarColor.class),any(BarStyle.class))).thenReturn(mock(BossBar.class));
            World world=mock(World.class);
            List<Player> players=new ArrayList<>();
            for (int i=0;i<4;i++) {
                Player player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                when(player.getName()).thenReturn("P"+i); when(player.getWorld()).thenReturn(world);
                when(player.getLocation()).thenReturn(new Location(world,357,-30,-118)); players.add(player);
            }
            RoundContext context=mock(RoundContext.class);
            Set<UUID> ids=new LinkedHashSet<>(players.stream().map(Player::getUniqueId).toList());
            when(context.participants()).thenReturn(ids);
            when(context.onlineParticipants()).thenReturn(players); when(context.state(any())).thenReturn(RoundPlayerState.ACTIVE);
            when(context.definition()).thenReturn(new MinigameDefinition("tag","Tag",true,true,false,4,14,1,
                    Optional.of(new CuboidRegion("world",new BlockPosition(327,-34,-90),new BlockPosition(378,-8,-141))),
                    List.of(),Optional.empty(),150,Map.of(),"test"));
            TagRoleDisplay roles=mock(TagRoleDisplay.class);
            TagMinigame game=new TagMinigame(mock(Plugin.class),roles); game.prepare(context); game.start(context);
            var field=TagMinigame.class.getDeclaredField("runtime"); field.setAccessible(true);
            TagRuntime runtime=(TagRuntime)field.get(game);
            Player attacker=players.stream().filter(p -> runtime.isTagger(p.getUniqueId())).findFirst().orElseThrow();
            Player victim=players.stream().filter(p -> !runtime.isTagger(p.getUniqueId())).findFirst().orElseThrow();
            var hit=mock(EntityDamageByEntityEvent.class); when(hit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            when(hit.getDamager()).thenReturn(attacker); when(hit.getEntity()).thenReturn(victim); when(context.elapsedTicks()).thenReturn(100L);
            assertEquals(EventDecision.DENY,game.onDamage(context,hit));
            verify(roles).update(attacker,false); verify(roles).update(victim,true);
            verify(victim).setVelocity(new org.bukkit.util.Vector(0,0.42,1.25));
            verify(world).playSound(victim.getLocation(),"minecraft:entity.player.attack.knockback",1f,1f);
            when(hit.getDamager()).thenReturn(victim); when(hit.getEntity()).thenReturn(attacker);
            when(context.elapsedTicks()).thenReturn(139L); game.onDamage(context,hit); verify(attacker,never()).setVelocity(any());
            when(context.elapsedTicks()).thenReturn(140L); game.onDamage(context,hit);
            assertTrue(runtime.isTagger(attacker.getUniqueId()));
            game.finish(context,RoundEndReason.TIME_LIMIT); game.reset(context); verify(roles,times(2)).clear();
        }
    }
}
