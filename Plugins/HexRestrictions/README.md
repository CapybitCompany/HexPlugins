# HexRestrictions

Config-driven plugin for Purpur/Paper 1.21.11 that removes forbidden vanilla items and enchantments from Hex SMP.

## Default policy

`minecraft:mending` is forbidden out of the box.

The plugin does not only disable repairing. It also removes Mending from ItemStacks and blocks the main acquisition paths:

- enchanting (`EnchantItemEvent`),
- anvils and smithing/crafting results,
- loot tables and entity/block drops,
- item entities and pickups,
- hopper movement,
- villager/merchant acquisition, restock and purchase,
- player inventory changes,
- player inventory + Ender Chest scans,
- container scans on open/chunk load,
- old villager offers on chunk scans,
- runtime Mending effect (`PlayerItemMendEvent`) as a final fail-safe.

Existing loaded chunks are processed in batches so a reload does not scan every container in one tick.

## Config examples

Disable more enchantments:

```yml
restrictions:
  forbidden-enchantments:
    - minecraft:mending
    - minecraft:infinity
```

Disable entire item materials:

```yml
restrictions:
  forbidden-items:
    - ELYTRA
    - TOTEM_OF_UNDYING
    - minecraft:end_crystal
```

For `forbidden-items`, entries are Bukkit `Material` values. For enchantments use namespaced enchantment keys.

## Commands

- `/hexrestrictions status`
- `/hexrestrictions reload`
- `/hexrestrictions scan players`
- `/hexrestrictions scan loaded`
- `/hexrestrictions scan <player>`

Admin permission: `hexrestrictions.admin` (default: OP).

## Build

- Java 21
- Paper API `1.21.11-R0.1-SNAPSHOT`
- output: `HexRestrictions-1.0.0.jar`

The `settings.gradle` uses the same shared Hex repository settings when `../gradle/standalone-plugin-settings.gradle` exists, but also has a standalone fallback because this plugin has no compile-time dependency on the other Hex modules.
