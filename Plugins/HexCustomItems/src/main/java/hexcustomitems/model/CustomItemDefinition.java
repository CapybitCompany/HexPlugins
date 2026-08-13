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
        String key,
        String id,
        int modelData,
        Material material,
        String name,
        List<String> lore,
        boolean canDrop,
        boolean canUseInAnvil,
        boolean glint,
        String permission,
        int cooldownSeconds,
        int adminPanelStack,
        int charges,
        List<ItemAction> actions
) {
    public CustomItemDefinition(
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
        this(id, id, 0, material, name, lore, !dropProtection, false, false,
                permission, cooldownSeconds, 1, charges, actions);
    }

    public CustomItemDefinition {
        key = Objects.requireNonNull(key, "key").toLowerCase(java.util.Locale.ROOT);
        id = Objects.requireNonNull(id, "id");
        modelData = Math.max(0, modelData);
        material = Objects.requireNonNull(material, "material");
        name = Objects.requireNonNull(name, "name");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        cooldownSeconds = Math.max(0, cooldownSeconds);
        adminPanelStack = Math.max(1, adminPanelStack);
        charges = Math.max(0, charges);
        if (key.isBlank()) {
            throw new IllegalArgumentException("key cannot be blank");
        }
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

    /** Existing listener compatibility: true means Q/drop should be blocked. */
    public boolean dropProtection() {
        return !canDrop;
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
