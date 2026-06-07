# HexCollections

Config-driven town/COOP collection engine for Hex SMP.

## MVP scope

- Collections are global for `townId` / COOP profile, not private per player.
- Loads collection definitions from `collections/*.yml`.
- Default collections:
  - `mining.cobblestone`
  - `mining.iron`
- Each default collection has 7 levels.
- Progress is written through `HexCore` SQL services.
- Runtime reads use RAM cache, not SQL per placeholder/GUI refresh.
- Registers public `HexCollectionsApi` through Bukkit `ServicesManager`.
- Registers `TownDataNamespace` named `collections` for `HexTowns` purge/reset.
- Supports trusted minion progress through `MINION_COLLECT` / `minions.resource.claimed`.
- Tracks player-placed blocks and recently-broken blocks for MVP anti-exploit.

## Commands

```text
/hexcollections info
/hexcollections reload
/hexcollections flush
```

## PlaceholderAPI

Identifier: `hexcollections`

Examples:

```text
%hexcollections_amount_mining_cobblestone%
%hexcollections_level_mining_cobblestone%
%hexcollections_next_level_mining_cobblestone%
%hexcollections_remaining_mining_cobblestone%
%hexcollections_progress_percent_mining_cobblestone%
%hexcollections_progress_bar_mining_cobblestone%
%hexcollections_unlocked_mining_cobblestone_3%
%hexcollections_reward_claimed_mining_cobblestone_3%
%hexcollections_rank_mining_cobblestone%
%hexcollections_gui_state_mining_cobblestone_1%
%hexcollections_gui_material_mining_cobblestone_1%
%hexcollections_gui_display_mining_cobblestone_1%
%hexcollections_gui_lore_mining_cobblestone_1%
```

Aliases are supported, for example `mining.cobblestone`, `mining_cobblestone`, and `cobblestone`.

## Storage

Tables:

```text
collection_progress
collection_events
```

Actual table names are prefixed by `HexCore` `Db#t(...)`.

## Performance model

- `addProgress(...)` increments RAM cache and marks town data dirty.
- Dirty data is flushed in batch every `storage.flush_interval_seconds`.
- Level-up can force immediate town flush via `storage.flush_on_level_up`.
- Placeholder/GUI reads never query SQL directly.
- Town purge deletes cache and SQL rows.

## Public API

```java
HexCollectionsApi api = Bukkit.getServicesManager()
    .getRegistration(HexCollectionsApi.class)
    .getProvider();

long amount = api.getAmount(townId, "mining.cobblestone");
int level = api.getLevel(townId, "mining.cobblestone");
```
