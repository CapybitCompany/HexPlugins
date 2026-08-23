# HexRestrictions 1.0.0 – implementation notes

## Goal

Provide one central, config-driven place for gameplay content that must not exist or work on Hex SMP.
The initial rule is `minecraft:mending`.

## Enforcement layers

1. **Creation/generation** – enchanting, loot, entity/block drops, crafting/smithing/anvil results.
2. **Trading** – new villager trades, replenishment and final merchant purchases.
3. **World transfer** – item spawns, entity/hopper pickup, hopper inventory moves and dispensers.
4. **Player inventory** – join/respawn, Paper slot-change event, inventory interactions and a low-frequency periodic sweep.
5. **Persistent world state** – inventories, item entities, item frames, item displays and villager offers are cleaned as chunks load. Already loaded chunks are queued in small batches on enable.
6. **Runtime fail-safe** – `PlayerItemMendEvent` is cancelled if Mending somehow survives another source.

## Important persistence boundary

The plugin deliberately does not edit unloaded `.mca` region files directly. A forbidden item can therefore remain as dormant NBT inside an unloaded legacy chunk until that chunk is loaded. On chunk load it is sanitized before normal ongoing gameplay. This avoids risky direct world-file mutation.

## Default rule

```yml
restrictions:
  forbidden-items: []
  forbidden-enchantments:
    - minecraft:mending
```

Adding another vanilla material or enchantment requires only a config change and `/hexrestrictions reload`.
