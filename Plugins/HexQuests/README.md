# HexQuests

Config-driven quest engine for Hex SMP.

## MVP
- Loads quests from `quests.yml` and daily pool slots from `daily-pools.yml`.
- Assigns daily quests per `townId + playerUuid + date`.
- Updates objective progress from `TriggerService` triggers.
- Stores progress in SQL through `HexCore`.
- Registers purge namespace `quests`.

Commands: `/hexquests info`, `/hexquests reload`.

