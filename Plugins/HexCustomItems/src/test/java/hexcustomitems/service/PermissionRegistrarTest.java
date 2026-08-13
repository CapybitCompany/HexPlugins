package hexcustomitems.service;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PermissionRegistrarTest extends PluginTestBase {

    private CustomItemDefinition withPermission(String id, String permission) {
        return TestConfig.item(id, Material.PAPER, permission, 0, 0,
                List.of(new hexcustomitems.model.MessageAction("<green>hi", false)));
    }

    private CustomItemDefinition withoutPermission(String id) {
        return TestConfig.item(id, Material.PAPER, null, 0, 0,
                List.of(new hexcustomitems.model.MessageAction("<green>hi", false)));
    }

    @Test
    void itemWithoutPermissionRegistersNoNodeAndStaysFree() {
        CustomItemDefinition free = withoutPermission("free_item");
        assertFalse(free.hasPermission());

        new PermissionRegistrar(plugin).apply(List.of(free), "op");

        // Es darf kein Node zu diesem Item existieren.
        assertNull(server.getPluginManager().getPermission("hex.items.use.free_item"));
    }

    @Test
    void itemWithPermissionRegistersNodeWithConfiguredDefault() {
        PermissionRegistrar registrar = new PermissionRegistrar(plugin);
        registrar.apply(List.of(withPermission("op_item", "hex.items.use.op_item")), "op");

        Permission permission = server.getPluginManager().getPermission("hex.items.use.op_item");
        assertNotNull(permission);
        assertEquals(PermissionDefault.OP, permission.getDefault());
    }

    @Test
    void supportsAllDefaultLevels() {
        assertDefault("true", PermissionDefault.TRUE, "lvl_true");
        assertDefault("false", PermissionDefault.FALSE, "lvl_false");
        assertDefault("not-op", PermissionDefault.NOT_OP, "lvl_notop");
        assertDefault("op", PermissionDefault.OP, "lvl_op");
    }

    private void assertDefault(String raw, PermissionDefault expected, String id) {
        String node = "hex.items.use." + id;
        new PermissionRegistrar(plugin).apply(List.of(withPermission(id, node)), raw);
        assertEquals(expected, server.getPluginManager().getPermission(node).getDefault());
    }

    @Test
    void reloadRemovesPreviouslyRegisteredNodes() {
        PermissionRegistrar registrar = new PermissionRegistrar(plugin);
        registrar.apply(List.of(withPermission("first", "hex.items.use.first")), "true");
        assertNotNull(server.getPluginManager().getPermission("hex.items.use.first"));

        registrar.apply(List.of(withPermission("second", "hex.items.use.second")), "true");

        assertNull(server.getPluginManager().getPermission("hex.items.use.first"),
                "Alter Node sollte beim Reload entfernt sein");
        assertNotNull(server.getPluginManager().getPermission("hex.items.use.second"));
    }
}
