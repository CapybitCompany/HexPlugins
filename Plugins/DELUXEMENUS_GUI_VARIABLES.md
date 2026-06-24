
# DeluxeMenus — dokumentacja zmiennych, placeholderów i wartości dla GUI Hex SMP

> Ten dokument opisuje **realnie dostępne teraz** zmienne i wartości, których można użyć w `DeluxeMenus`
> do budowy interfejsów graficznych opartych o aktualnie zaimplementowane moduły.
>
> Stan repo na dziś:
> - `HexCollections` ma gotową integrację z `PlaceholderAPI`.
> - `HexMinions` ma własną ekspansję `PlaceholderAPI` (`hexminions`) dla danych miasta, listy i wybranego miniona.
> - `HexSkills` i `HexQuests` nie wystawiają jeszcze placeholderów do `DeluxeMenus`.

---

## 1. Co jest gotowe do użycia w DeluxeMenus już teraz

Na ten moment w `DeluxeMenus` można stabilnie używać placeholderów z modułów:

- `HexCollections`
- `HexMinions`

Identyfikator PlaceholderAPI:

```text
hexcollections
```

Przykład ogólnego formatu:

```text
%hexcollections_<placeholder>%
```

---

## 2. Wymagania integracyjne

Żeby placeholdery działały w `DeluxeMenus`, serwer musi mieć:

1. `HexCore`
2. `HexTowns`
3. `HexCollections`
4. `PlaceholderAPI`
5. `DeluxeMenus`

`HexCollections` i `HexMinions` rejestrują własne ekspansje PlaceholderAPI automatycznie, jeśli `PlaceholderAPI` jest obecne.

---

## 3. Kontekst działania placeholderów

Wszystkie placeholdery `HexCollections` działają w kontekście:

- aktualnego gracza,
- jego bieżącego `townId`, ustalonego przez `TownsApi#townIdOf(playerUuid)`.

To oznacza:

- placeholdery **nie pokazują progresu prywatnego gracza**,
- placeholdery pokazują **progres kolekcji miasta/COOP**, do którego należy gracz,
- jeśli gracz nie należy do miasta, większość placeholderów zwróci wartości zerowe lub puste.

---

## 4. ID kolekcji i aliasy

Aktualnie zaimplementowane kolekcje MVP:

- `mining.cobblestone`
- `mining.iron`

System obsługuje aliasy, więc w placeholderach można używać kilku form tego samego ID.

### 4.1 `mining.cobblestone`

Dozwolone warianty:

```text
mining.cobblestone
mining_cobblestone
cobblestone
minecraft:cobblestone
```

### 4.2 `mining.iron`

Dozwolone warianty:

```text
mining.iron
mining_iron
iron
iron_ingot
minecraft:iron_ingot
```

### Rekomendacja

Do konfiguracji GUI najlepiej używać formy:

```text
mining_cobblestone
mining_iron
```

Dlaczego:

- jest kompatybilna z parserami placeholderów,
- nie zawiera kropki,
- jest czytelna w `DeluxeMenus`.

---

## 5. Placeholdery ogólne kolekcji

## 5.1 Ilość zebranych surowców

Format:

```text
%hexcollections_amount_<collection_id>%
```

Przykłady:

```text
%hexcollections_amount_mining_cobblestone%
%hexcollections_amount_mining_iron%
```

Zwracana wartość:

```text
0
128
5320
```

Typ wartości:

- liczba całkowita (`long`)

Opis:

- pokazuje aktualny zbuforowany progres kolekcji miasta,
- odczyt idzie z RAM cache, nie z SQL.

---

## 5.2 Aktualny poziom kolekcji

Format:

```text
%hexcollections_level_<collection_id>%
```

Przykłady:

```text
%hexcollections_level_mining_cobblestone%
%hexcollections_level_mining_iron%
```

Zwracana wartość:

```text
0
1
4
7
```

Typ wartości:

- liczba całkowita (`int`)

Opis:

- zwraca aktualny odblokowany poziom kolekcji.

---

## 5.3 Następny poziom kolekcji

Format:

```text
%hexcollections_next_level_<collection_id>%
```

Przykłady:

```text
%hexcollections_next_level_mining_cobblestone%
%hexcollections_next_level_mining_iron%
```

Zwracana wartość:

```text
1
2
5
7
```

Opis:

- zwraca numer następnego poziomu,
- jeśli kolekcja jest już na maksie, zwraca poziom maksymalny.

---

## 5.4 Wymagany próg dla konkretnego poziomu

Format:

```text
%hexcollections_required_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_required_mining_cobblestone_1%
%hexcollections_required_mining_cobblestone_7%
%hexcollections_required_mining_iron_3%
```

Zwracana wartość:

```text
50
30000
1000
```

Opis:

- zwraca próg wymagany do osiągnięcia podanego poziomu.

---

## 5.5 Pozostała ilość do następnego poziomu

Format:

```text
%hexcollections_remaining_<collection_id>%
```

Przykłady:

```text
%hexcollections_remaining_mining_cobblestone%
%hexcollections_remaining_mining_iron%
```

Zwracana wartość:

```text
50
122
0
```

Opis:

- zwraca ile jeszcze brakuje do kolejnego poziomu.

---

## 5.6 Procent progresu

Format:

```text
%hexcollections_progress_percent_<collection_id>%
```

Przykłady:

```text
%hexcollections_progress_percent_mining_cobblestone%
%hexcollections_progress_percent_mining_iron%
```

Zwracana wartość:

```text
0.00
12.50
87.33
100.00
```

Typ wartości:

- tekst liczbowy formatowany do 2 miejsc po przecinku

Opis:

- procent progresu do kolejnego poziomu,
- przy maksymalnym poziomie zwraca `100.00`.

---

## 5.7 Pasek progresu

Format:

```text
%hexcollections_progress_bar_<collection_id>%
```

Przykłady:

```text
%hexcollections_progress_bar_mining_cobblestone%
%hexcollections_progress_bar_mining_iron%
```

Zwracana wartość, np.:

```text
■■■■■■□□□□□□
■■■■■■■■■■■■■■■■■■■■
```

Opis:

- tekstowy pasek progresu,
- długość bierze z konfiguracji kolekcji,
- aktualnie zwracany bez kolorów z configu, jako znaki `■` i `□`.

---

## 5.8 Czy dany poziom jest odblokowany

Format:

```text
%hexcollections_unlocked_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_unlocked_mining_cobblestone_1%
%hexcollections_unlocked_mining_cobblestone_3%
%hexcollections_unlocked_mining_iron_7%
```

Zwracana wartość:

```text
true
false
```

Opis:

- przydatne w `view_requirement`,
- zwraca zwykłe `true` / `false`.

---

## 5.9 Czy nagroda jest „claimed”

Format:

```text
%hexcollections_reward_claimed_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_reward_claimed_mining_cobblestone_1%
%hexcollections_reward_claimed_mining_iron_4%
```

Aktualna semantyka MVP:

- placeholder zwraca to samo co `unlocked`, bo reward claim mode w MVP jest de facto `AUTO`,
- nie ma jeszcze osobnej warstwy persistent claim-state.

Zwracana wartość:

```text
true
false
```

---

## 5.10 Ranking kolekcji

Format:

```text
%hexcollections_rank_<collection_id>%
```

Przykład:

```text
%hexcollections_rank_mining_cobblestone%
```

Aktualna wartość MVP:

```text
-
```

Opis:

- placeholder jest zarezerwowany pod przyszłość,
- ranking kolekcji nie jest jeszcze zaimplementowany.

---

## 6. Placeholdery GUI poziomów kolekcji

Ta grupa jest przygotowana specjalnie pod `DeluxeMenus`.

## 6.1 Stan poziomu w GUI

Format:

```text
%hexcollections_gui_state_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_gui_state_mining_cobblestone_1%
%hexcollections_gui_state_mining_cobblestone_2%
%hexcollections_gui_state_mining_iron_5%
```

Możliwe wartości:

```text
LOCKED
IN_PROGRESS
UNLOCKED
```

### Znaczenie

- `LOCKED` — poziom jeszcze nie jest aktualnie odblokowywany,
- `IN_PROGRESS` — to najbliższy poziom, nad którym obecnie pracuje miasto,
- `UNLOCKED` — poziom został osiągnięty.

### Uwaga

W dokumentacji koncepcyjnej pojawia się też wartość `CLAIMED`, ale w aktualnym MVP:

- GUI state placeholder **jeszcze jej nie zwraca**,
- ponieważ reward claim system manualny nie jest jeszcze zaimplementowany.

---

## 6.2 Materiał do GUI

Format:

```text
%hexcollections_gui_material_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_gui_material_mining_cobblestone_1%
%hexcollections_gui_material_mining_cobblestone_4%
%hexcollections_gui_material_mining_iron_7%
```

Możliwe wartości aktualnego MVP:

```text
RED_STAINED_GLASS_PANE
YELLOW_STAINED_GLASS_PANE
LIME_STAINED_GLASS_PANE
```

Mapowanie:

- `LOCKED` -> `RED_STAINED_GLASS_PANE`
- `IN_PROGRESS` -> `YELLOW_STAINED_GLASS_PANE`
- `UNLOCKED` -> `LIME_STAINED_GLASS_PANE`

### Uwaga

W przyszłości można rozszerzyć to o `GREEN_STAINED_GLASS_PANE` dla `CLAIMED`.

---

## 6.3 Nazwa poziomu do GUI

Format:

```text
%hexcollections_gui_display_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_gui_display_mining_cobblestone_1%
%hexcollections_gui_display_mining_iron_3%
```

Zwracana wartość, np.:

```text
&7Cobblestone 1
&fIron 3
```

Opis:

- zwraca uproszczoną nazwę poziomu do wyświetlenia w GUI.

---

## 6.4 Lore poziomu do GUI

Format:

```text
%hexcollections_gui_lore_<collection_id>_<level>%
```

Przykłady:

```text
%hexcollections_gui_lore_mining_cobblestone_1%
%hexcollections_gui_lore_mining_iron_2%
```

Zwracana wartość, np.:

```text
Postęp: 128/250
```

Opis:

- aktualnie zwracana jest pojedyncza linia tekstu,
- jeśli chcesz w `DeluxeMenus` zbudować wieloliniowe lore, najlepiej użyć kilku własnych linii i wstawić tam placeholdery `amount`, `required`, `remaining`, `progress_percent`, `progress_bar`.

---

## 7. Gotowe przykłady do DeluxeMenus

## 7.1 Prosty slot poziomu kolekcji

```yaml
items:
  cobblestone_level_1:
    material: "%hexcollections_gui_material_mining_cobblestone_1%"
    slot: 10
    display_name: "%hexcollections_gui_display_mining_cobblestone_1%"
    lore:
      - "&7Postęp: &f%hexcollections_amount_mining_cobblestone%&7/&f%hexcollections_required_mining_cobblestone_1%"
      - "&7Brakuje: &f%hexcollections_remaining_mining_cobblestone%"
      - "&7Status: &f%hexcollections_gui_state_mining_cobblestone_1%"
      - "&7Procent: &f%hexcollections_progress_percent_mining_cobblestone%%%"
      - ""
      - "%hexcollections_progress_bar_mining_cobblestone%"
```

---

## 7.2 View requirement po stanie GUI

```yaml
items:
  cobblestone_level_1_locked:
    material: RED_STAINED_GLASS_PANE
    slot: 10
    priority: 10
    view_requirement:
      requirements:
        locked:
          type: string equals
          input: "%hexcollections_gui_state_mining_cobblestone_1%"
          output: "LOCKED"
    display_name: "&cCobblestone I"

  cobblestone_level_1_progress:
    material: YELLOW_STAINED_GLASS_PANE
    slot: 10
    priority: 20
    view_requirement:
      requirements:
        progress:
          type: string equals
          input: "%hexcollections_gui_state_mining_cobblestone_1%"
          output: "IN_PROGRESS"
    display_name: "&eCobblestone I"

  cobblestone_level_1_unlocked:
    material: LIME_STAINED_GLASS_PANE
    slot: 10
    priority: 30
    view_requirement:
      requirements:
        unlocked:
          type: string equals
          input: "%hexcollections_gui_state_mining_cobblestone_1%"
          output: "UNLOCKED"
    display_name: "&aCobblestone I"
```

---

## 7.3 Ikona główna kolekcji

```yaml
items:
  cobblestone_collection:
    material: COBBLESTONE
    slot: 10
    display_name: "&7Kolekcja Cobblestone"
    lore:
      - "&7Poziom: &f%hexcollections_level_mining_cobblestone%"
      - "&7Następny poziom: &f%hexcollections_next_level_mining_cobblestone%"
      - "&7Ilość: &f%hexcollections_amount_mining_cobblestone%"
      - "&7Do następnego: &f%hexcollections_remaining_mining_cobblestone%"
      - ""
      - "%hexcollections_progress_bar_mining_cobblestone%"
```

---

## 8. Jakie wartości są bezpieczne do porównań w `view_requirement`

Najbezpieczniej porównywać:

### Booleany

```text
true
false
```

Dla:

- `%hexcollections_unlocked_<collection>_<level>%`
- `%hexcollections_reward_claimed_<collection>_<level>%`

### Stany tekstowe

```text
LOCKED
IN_PROGRESS
UNLOCKED
```

Dla:

- `%hexcollections_gui_state_<collection>_<level>%`

### Materiały

```text
RED_STAINED_GLASS_PANE
YELLOW_STAINED_GLASS_PANE
LIME_STAINED_GLASS_PANE
```

Dla:

- `%hexcollections_gui_material_<collection>_<level>%`

### Liczby

Jako tekst liczbowy:

```text
0
1
7
250
30000
100.00
```

Dla:

- `amount`
- `level`
- `next_level`
- `required`
- `remaining`
- `progress_percent`

---

## 9. Co nie jest jeszcze dostępne jako placeholder

## 9.1 `HexMinions`

`HexMinions` wystawia ekspansję PlaceholderAPI o identyfikatorze:

```text
hexminions
```

W kodzie istnieją dane, które nadają się pod przyszłe placeholdery:

### Dane miasta minionów

Z `TownMinionMenuData`:

- `townUuid`
- `townName`
- `minionCount`
- `minionLimit`
- lista minionów

### Dane pojedynczego miniona

Z `MinionMenuData`:

- `id`
- `shortId`
- `typeId`
- `displayName`
- `tier`
- `maxTier`
- `world`
- `x`, `y`, `z`
- `storageUsed`
- `storageLimit`
- `storagePercent`
- `actionTimeSeconds`
- `state`
- `canUpgrade`
- `nextUpgradeRequirementsText`
- `menuSlotHint`

Do `DeluxeMenus` można używać między innymi:

```text
%hexminions_count%
%hexminions_limit%
%hexminions_selected_tier%
%hexminions_selected_storage_percent%
%hexminions_index_1_name%
```

Placeholdery `selected_*` działają po ustawieniu kontekstu wybranego miniona, np. przez otwarcie menu miniona lub komendę/akcję wybierającą konkretny minion.

---

## 9.2 `HexSkills`

`HexSkills` nie wystawia jeszcze placeholderów do GUI.

---

## 9.3 `HexQuests`

`HexQuests` nie wystawia jeszcze placeholderów do GUI.

---

## 10. Elementy GUI, które możesz już zbudować

Na dziś bez dodatkowego kodu możesz zbudować w `DeluxeMenus`:

- ekran listy kolekcji,
- ekran progresu pojedynczej kolekcji,
- ekran poziomów kolekcji 1–7,
- ikonę stanu kolekcji,
- pasek progresu,
- sekcję informacji o odblokowaniach,
- warunkowe pokazywanie itemów przez `view_requirement`.

Najlepsze use-case’y teraz:

1. Menu główne kolekcji (`Cobblestone`, `Iron`).
2. Menu szczegółów jednej kolekcji.
3. Rząd 7 slotów pokazujących poziomy kolekcji.
4. Dynamiczny lore z progressem i stanem.

---

## 11. Rekomendowany standard nazewnictwa w DeluxeMenus

Polecam przyjąć nazwy itemów typu:

```text
collection_cobblestone_icon
collection_cobblestone_level_1
collection_cobblestone_level_2
collection_iron_icon
collection_iron_level_1
```

I trzymać wszędzie aliasy z underscore:

```text
mining_cobblestone
mining_iron
```

To upraszcza konfigurację i zmniejsza ryzyko problemów z parserami placeholderów.

---

## 12. Szybka ściąga

### Najczęściej używane placeholdery

```text
%hexcollections_amount_mining_cobblestone%
%hexcollections_level_mining_cobblestone%
%hexcollections_next_level_mining_cobblestone%
%hexcollections_required_mining_cobblestone_1%
%hexcollections_remaining_mining_cobblestone%
%hexcollections_progress_percent_mining_cobblestone%
%hexcollections_progress_bar_mining_cobblestone%
%hexcollections_gui_state_mining_cobblestone_1%
%hexcollections_gui_material_mining_cobblestone_1%
```

### Najważniejsze stany

```text
LOCKED
IN_PROGRESS
UNLOCKED
```

### Najważniejsze booleany

```text
true
false
```

### Najważniejsze materiały

```text
RED_STAINED_GLASS_PANE
YELLOW_STAINED_GLASS_PANE
LIME_STAINED_GLASS_PANE
```

---

## 13. Co warto dopisać w następnej iteracji

Jeżeli chcesz, kolejnym krokiem mogę przygotować też:

1. **osobną ekspansję PlaceholderAPI dla `HexMinions`**,
2. **gotowe pliki `DeluxeMenus` YAML** dla menu kolekcji,
3. **placeholdery dla `HexSkills`**,
4. **placeholdery dla `HexQuests`**,
5. dynamiczne `CLAIMED` i manual claim GUI dla nagród kolekcji.

