package hexcustomitems.service;

import hexcustomitems.model.CustomItemDefinition;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Registriert die Permissions konfigurierter Items dynamisch zur Laufzeit.
 *
 * <p>Damit funktionieren neue Config-Items ohne Änderung an der plugin.yml:
 * setzt ein Item {@code permission:}, wird der Node beim Enable/Reload mit dem
 * konfigurierten Default angemeldet. Items ohne {@code permission:} sind frei nutzbar.
 */
public final class PermissionRegistrar {

    private final JavaPlugin plugin;
    private final Set<String> registered = new HashSet<>();

    public PermissionRegistrar(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void apply(Collection<CustomItemDefinition> items, String rawDefault) {
        PermissionDefault permissionDefault = parseDefault(rawDefault);
        PluginManager pluginManager = plugin.getServer().getPluginManager();

        // Zuvor selbst registrierte Nodes wieder entfernen (sauberer Reload).
        for (String node : registered) {
            Permission existing = pluginManager.getPermission(node);
            if (existing != null) {
                pluginManager.removePermission(existing);
            }
        }
        registered.clear();

        for (CustomItemDefinition item : items) {
            if (!item.hasPermission()) {
                continue;
            }
            String node = item.permission();
            // Extern (z.B. in plugin.yml) definierte Nodes nicht überschreiben.
            if (pluginManager.getPermission(node) != null) {
                continue;
            }
            pluginManager.addPermission(new Permission(node, "HexCustomItems item: " + item.id(), permissionDefault));
            registered.add(node);
        }
    }

    public void clear() {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        for (String node : registered) {
            Permission existing = pluginManager.getPermission(node);
            if (existing != null) {
                pluginManager.removePermission(existing);
            }
        }
        registered.clear();
    }

    private PermissionDefault parseDefault(String raw) {
        if (raw == null) {
            return PermissionDefault.TRUE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "op", "true-op" -> PermissionDefault.OP;
            case "false" -> PermissionDefault.FALSE;
            case "notop", "not-op", "not_op" -> PermissionDefault.NOT_OP;
            default -> PermissionDefault.TRUE;
        };
    }
}
