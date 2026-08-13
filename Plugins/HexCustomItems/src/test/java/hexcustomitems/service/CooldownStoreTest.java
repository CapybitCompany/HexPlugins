package hexcustomitems.service;

import hexcustomitems.support.PluginTestBase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownStoreTest extends PluginTestBase {

    @Test
    void writeThenReadRoundtrips() {
        CooldownStore store = new CooldownStore(plugin, "test-cooldowns.yml");
        UUID player = UUID.randomUUID();
        long expiry = System.currentTimeMillis() + 60_000L;

        Map<UUID, Map<String, Long>> data = new HashMap<>();
        Map<String, Long> perItem = new HashMap<>();
        perItem.put("jump_potion", expiry);
        data.put(player, perItem);

        store.write(data);
        Map<UUID, Map<String, Long>> read = store.read();

        assertTrue(read.containsKey(player));
        assertEquals(expiry, read.get(player).get("jump_potion"));
    }

    @Test
    void readMissingFileReturnsEmpty() {
        CooldownStore store = new CooldownStore(plugin, "does-not-exist.yml");
        assertTrue(store.read().isEmpty());
    }
}
