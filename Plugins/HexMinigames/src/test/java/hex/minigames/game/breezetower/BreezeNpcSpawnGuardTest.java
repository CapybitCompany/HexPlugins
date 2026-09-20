package hex.minigames.game.breezetower;
import org.bukkit.entity.Breeze;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

final class BreezeNpcSpawnGuardTest {
    @Test void exemptsOnlyTheNpcBeingSpawnedAndLeavesOtherMobsAlone() {
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        Breeze owned = mock(Breeze.class), other = mock(Breeze.class);
        when(owned.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var guard = new BreezeNpcSpawnGuard(plugin)) {
            guard.include(owned);
            var ownEvent = mock(CreatureSpawnEvent.class);
            when(ownEvent.getEntity()).thenReturn(owned);
            guard.onSpawn(ownEvent);
            verify(ownEvent).setCancelled(false);
            var foreignEvent = mock(CreatureSpawnEvent.class);
            when(foreignEvent.getEntity()).thenReturn(other);
            guard.onSpawn(foreignEvent);
            verify(foreignEvent, never()).setCancelled(false);
        }
    }
}
