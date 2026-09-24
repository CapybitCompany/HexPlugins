package hex.minigames.persistence;

import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real SQLite additive migration and exact controller field round-trip. */
class ControllerSnapshotMigrationTest {
    @TempDir Path directory;
    @Test void existingPendingSnapshotSurvivesMigrationAndControllerFieldsRoundTrip() throws Exception {
        Plugin plugin=mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        PlayerSnapshotRepository repository=new PlayerSnapshotRepository(plugin); repository.initialize(); assertTrue(repository.available());
        UUID id=UUID.randomUUID();
        StoredPlayerState state=new StoredPlayerState(id,"Pilot",UUID.randomUUID(),1234,true,
                new ItemStack[0],new ItemStack[0],new ItemStack[0],null,3,GameMode.ADVENTURE,true,true,
                .5f,7,80,20,0,20,5,0,List.of(),"world",2,64,3,12,34,1.23,.17f,.23f,true,false,false,true);
        var bind=PlayerSnapshotRepository.class.getDeclaredMethod("bind",PreparedStatement.class,StoredPlayerState.class); bind.setAccessible(true);
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("snapshots.db"))) {
            List<String> columns=new ArrayList<>();
            try(var query=connection.createStatement(); var rows=query.executeQuery("PRAGMA table_info(minigames_snapshots)")) {
                while(rows.next()) columns.add(rows.getString("name"));
            }
            // Column order mirrors the historical schema plus append-only controller fields.
            try(var insert=connection.prepareStatement("INSERT INTO minigames_snapshots ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")")) {
                bind.invoke(repository,insert,state); insert.executeUpdate();
            }
            var loaded=repository.find(id).orElseThrow();
            assertEquals(1.23,loaded.scale()); assertEquals(.17f,loaded.flySpeed()); assertEquals(.23f,loaded.walkSpeed());
            assertTrue(loaded.invisible()); assertFalse(loaded.collidable()); assertFalse(loaded.gravity()); assertTrue(loaded.invulnerable());
            // Recreate the old schema while retaining its pending row.
            try(var statement=connection.createStatement()) {
                for(String name:List.of("scale","fly_speed","walk_speed","invisible","collidable","gravity","invulnerable"))
                    statement.executeUpdate("ALTER TABLE minigames_snapshots DROP COLUMN "+name);
            }
        }
        repository.initialize(); repository.initialize(); assertTrue(repository.available());
        var migrated=repository.find(id).orElseThrow();
        assertTrue(migrated.restorePending()); assertEquals(state.instanceId(),migrated.instanceId()); assertEquals(3,migrated.heldSlot());
        assertEquals("world",migrated.worldName()); assertEquals(GameMode.ADVENTURE,migrated.gameMode());
        assertEquals(1.0,migrated.scale()); assertEquals(.1f,migrated.flySpeed()); assertEquals(.2f,migrated.walkSpeed());
        assertTrue(migrated.collidable()); assertTrue(migrated.gravity()); assertFalse(migrated.invisible());
    }
}
