package hexcustomitems.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

/**
 * Definition eines Custom-Items aus der Config.
 *
 * <p>{@code permission} ist optional: {@code null} bedeutet "für alle nutzbar".
 * Ist eine Permission gesetzt, wird sie beim Enable/Reload dynamisch registriert
 * (siehe PermissionRegistrar), damit neue Config-Items ohne plugin.yml-Änderung
 * funktionieren.
 */
public record CustomItemDefinition(
        String id,
        Material material,
        String name,
        List<String> lore,
        boolean dropProtection,
        String permission,
        int cooldownSeconds,
        int charges,
        List<ItemAction> actions
) {
    public CustomItemDefinition {
        id = Objects.requireNonNull(id, "id");
        material = Objects.requireNonNull(material, "material");
        name = Objects.requireNonNull(name, "name");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        cooldownSeconds = Math.max(0, cooldownSeconds);
        charges = Math.max(0, charges);
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        // permission bewusst nullable: kein Objects.requireNonNull.
    }

    /** True, wenn dieses Item ein begrenztes Ladungs-System nutzt statt Stack-Verbrauch. */
    public boolean usesCharges() {
        return charges > 0;
    }

    /** True, wenn eine Permission konfiguriert wurde (sonst frei nutzbar). */
    public boolean hasPermission() {
        return permission != null && !permission.isBlank();
    }

    /** True, wenn mindestens eine Aktion offensiv ist und die Guard-Prüfung durchlaufen muss. */
    public boolean hasOffensiveAction() {
        for (ItemAction action : actions) {
            if (action.offensive()) {
                return true;
            }
        }
        return false;
    }
}
