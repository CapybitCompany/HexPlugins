# HexTowns menus for DeluxeMenus

Ten folder zawiera gotowy pakiet konfiguracji GUI dla `DeluxeMenus`, zaprojektowany tak, aby gracz mógł obsługiwać miasta głównie z menu graficznych.

## Pliki

- `town_main.yml` — główne centrum miast; pokazuje inne ikony dla gracza bez miasta i dla gracza z miastem.
- `town_manage.yml` — panel zarządzania istniejącym miastem.
- `town_claims.yml` — claim aktualnego chunka, lokalna mapa, `/town here`, `/town check`, `/town growth`.
- `town_coop.yml` — prośba o COOP, informacja dla właściciela o `/town accept <nick>`, opuszczanie COOP.
- `town_danger.yml` — akcje ryzykowne: destroy miasta lub opuszczenie COOP.
- `town_create_confirm.yml` — GUI potwierdzenia `/town create confirm`.
- `town_destroy_confirm.yml` — GUI potwierdzenia `/town destroy confirm`.
- `town_leave_confirm.yml` — GUI potwierdzenia `/town endcoop confirm`.
- `town_collections.yml` — podgląd kolekcji surowców miasta/COOP przez `HexCollections`.
- `town_collections_farming.yml` — osobna karta kolekcji farmerskich.
- `town_collections_animals.yml` — osobna karta kolekcji zwierzęcych.
- `town_minions.yml` — podgląd i akcje minionów miasta przez `HexMinions`.

## Wymagane pluginy

Minimum dla menu miast:

- HexCore
- HexTowns
- PlaceholderAPI
- DeluxeMenus

Integracje widoczne w menu:

- HexCollections — placeholdery kolekcji.
- HexMinions — placeholdery i akcje minionów.

Jeśli `HexCollections` albo `HexMinions` nie są obecne, ich placeholdery nie będą uzupełniane, ale podstawowe menu HexTowns nadal działa.

## Rejestracja w DeluxeMenus

Skopiuj pliki `.yml` do folderu menu DeluxeMenus i zarejestruj je w głównej konfiguracji DeluxeMenus zgodnie z wersją pluginu.

Sugerowane nazwy menu:

```text
town_main
town_manage
town_claims
town_coop
town_danger
town_create_confirm
town_destroy_confirm
town_leave_confirm
town_collections
town_collections_farming
town_collections_animals
town_minions
```

Główne komendy otwarcia:

```text
/miasto
/miasta
/townmenu
```

Celowo nie użyto `open_command: town`, żeby nie kolidować z natywną komendą `/town` pluginu HexTowns.

## Pokryte komendy gracza HexTowns

| Komenda | Menu / zachowanie |
| --- | --- |
| `/town create` | `town_main` → ikona `Załóż miasto`; potem `town_create_confirm`. |
| `/town create confirm` | `town_create_confirm`. |
| `/town claim` | `town_claims` → `Claimuj aktualny chunk`. |
| `/town coop` | `town_main` albo `town_coop`, widoczne dla gracza bez miasta. |
| `/town accept <nick>` | `town_coop` pokazuje instrukcję; samo `<nick>` wymaga wpisania lub kliknięcia wiadomości z chatu. |
| `/town endcoop` / `/town leave` | `town_coop` albo `town_danger` → `town_leave_confirm`. |
| `/town endcoop confirm` | `town_leave_confirm`. |
| `/town destroy` | `town_danger` dla właściciela. |
| `/town destroy confirm` | `town_destroy_confirm`. |
| `/town check` | `town_main` i `town_claims`. |
| `/town info` | `town_manage` i `town_claims`. |
| `/town here` | `town_main` i `town_claims`. |
| `/town map` | `town_main` i `town_claims`. |
| `/town growth` | `town_manage` i `town_claims`. |

## Placeholdery HexTowns używane przez menu

Identifier PlaceholderAPI:

```text
hextowns
```

Najważniejsze placeholdery:

```text
%hextowns_has_town%
%hextowns_is_owner%
%hextowns_is_coop%
%hextowns_role%
%hextowns_role_display%
%hextowns_town_uuid%
%hextowns_town_name%
%hextowns_owner_uuid%
%hextowns_owner_name%
%hextowns_members%
%hextowns_max_members%
%hextowns_chunks%
%hextowns_max_chunks%
%hextowns_growth%
%hextowns_world%
%hextowns_heart%
%hextowns_created_at%
%hextowns_current_chunk%
%hextowns_here_has_town%
%hextowns_here_town_name%
%hextowns_here_is_own%
%hextowns_can_build_here%
%hextowns_claim_cost_growth%
%hextowns_create_initial_chunks%
%hextowns_create_min_distance%
%hextowns_buffer_chunks%
%hextowns_confirm_seconds%
%hextowns_visual_radius%
```

Menu używa `view_requirement` z `%hextowns_has_town%`, `%hextowns_is_owner%` i `%hextowns_is_coop%`, dzięki czemu:

- gracz bez miasta widzi `Załóż miasto` i `Poproś o COOP`,
- po założeniu miasta ikona tworzenia znika i pojawia się panel zarządzania,
- właściciel widzi `Zniszcz miasto`,
- członek COOP widzi `Opuść COOP`.

## Placeholdery HexMinions

Identifier: `hexminions`

```text
%hexminions_has_town%
%hexminions_town_uuid%
%hexminions_town_name%
%hexminions_count%
%hexminions_limit%
%hexminions_remaining%
%hexminions_percent%
```

Minion po indeksie:

```text
%hexminions_index_<index>_<field>%
```

Najczęściej używane pola:

```text
exists, id, short_id, type, name, tier, max_tier,
world, x, y, z, location,
storage_used, storage_limit, storage_percent, storage_bar,
action_time_seconds, state, can_upgrade, requirements,
material, status_material
```

Komendy używane w menu minionów:

```text
/minion action collect <uuid>
/minion action open <uuid>
/minion action upgrade <uuid>
/minion action pickup <uuid>
/minion action move <uuid>
/minion select-index <index>
```

## Placeholdery HexCollections

Identifier: `hexcollections`

Aktualne kolekcje MVP:

```text
mining_cobblestone
mining_iron
```

Przykłady:

```text
%hexcollections_level_mining_cobblestone%
%hexcollections_amount_mining_cobblestone%
%hexcollections_remaining_mining_cobblestone%
%hexcollections_progress_percent_mining_cobblestone%
%hexcollections_progress_bar_mining_cobblestone%
%hexcollections_gui_material_mining_cobblestone_1%
%hexcollections_gui_display_mining_cobblestone_1%
%hexcollections_gui_state_mining_cobblestone_1%
```

## Uwagi logistyczne

- GUI potwierdzeń działa razem z tokenami pamięciowymi HexTowns: pierwsze kliknięcie wywołuje `/town destroy` albo `/town endcoop`, a ekran potwierdzenia wywołuje komendę `confirm`.
- `/town accept <nick>` nie da się w pełni obsłużyć statycznym YAML-em bez wpisania nicku; dlatego menu pokazuje instrukcję, a właściciel nadal dostaje klikalną wiadomość z chatu od HexTowns.
- `town_minions.yml` ma przygotowane sloty pod maksymalnie 21 pozycji GUI. Natywne menu pokazuje dokładnie tyle pozycji, ile wynosi limit miasta; w DeluxeMenus pozycje poza limitem są wygaszane placeholderami.
