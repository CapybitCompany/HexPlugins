# HexMinions

Miniony przypisane do miast `HexTowns`.

## MVP

- Miniony są stawiane tylko w obrębie miasta gracza.
- Limit minionów jest liczony per miasto (`minions.limit` w meta HexTowns + `limits.yml`).
- Minion ma wizualizację ArmorStand/TextDisplay, storage, tier, menu PPM, generowanie zasobów i relokację.
- `/minion move <id>` przenosi miniona do pozycji gracza w obrębie tego samego miasta.
- Dane są persystowane w DB przez HexCore.

## Komendy

```text
/minion give <player> <type> [tier] [amount]
/minion list
/minion pickup <id>
/minion move <id>
/minion select <id>
/minion select-index <index>
/minion action <collect|upgrade|pickup|move> <id>
/minion reload
/minion admin metrics
```

## DeluxeMenus future adapter

Core pluginu udostępnia stabilnie sortowane dane menu (`MinionMenuDataService`) oraz komendy akcji. Dzięki temu DeluxeMenus może używać placeholderów indeksowanych typu:

```text
%hexminions_town_count%
%hexminions_town_limit%
%hexminions_minion_1_name%
%hexminions_minion_1_tier%
%hexminions_minion_1_storage_percent%
```

Logika akcji zawsze zostaje po stronie HexMinions.

