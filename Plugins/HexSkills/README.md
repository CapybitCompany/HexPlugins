# HexSkills

Config-driven skill engine for Hex SMP.

## MVP
- Loads skill definitions from `skills.yml`.
- Subscribes to `TriggerService` triggers.
- Stores progress in SQL through `HexCore` `DatabaseService`.
- Uses `TOWN_PLAYER` scope.
- Registers `TownDataNamespace` named `skills` and purges data on town destroy.

## Commands
```text
/hexskills info
/hexskills reload
```

