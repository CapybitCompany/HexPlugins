# HexGUI

Lekki, niezależny hub GUI dla pluginów Hex SMP.

## Komendy

- `/hex` — otwiera hub dla gracza.
- `/hexgui reload` — przeładowuje `config.yml` (OP / `hexgui.admin`).

## Dlaczego plugin nie ma `depend` na HexCore/HexTowns/HexMinions/HexCollections?

Celowo. Hub ma się uruchomić nawet wtedy, gdy jeden z pluginów docelowych nie załaduje się poprawnie. Integracje są wpisane jako `softdepend`, a każda pozycja ma własne `required-plugins`.

Przy otwarciu GUI i ponownie bezpośrednio przed wykonaniem komendy sprawdzane jest:

1. czy wszystkie `required-plugins` istnieją i są włączone,
2. czy root komendy istnieje,
3. czy plugin będący właścicielem komendy nadal jest włączony,
4. opcjonalne uprawnienie pozycji,
5. wynik wykonania komendy i wyjątki.

W przypadku awarii gracz dostaje komunikat i wraca do huba zamiast otrzymać błąd lub utknąć w pustym GUI.

## Konfiguracja wpisu

```yaml
entries:
  example:
    enabled: true
    slot: 22
    name: "&6Przykład"
    lore:
      - "&7Opis"
    command: "somecommand arg"
    run-as: PLAYER       # PLAYER lub CONSOLE
    close-on-click: true
    required-plugins:
      - SomePlugin
    permission: ""
    icon:
      material: PLAYER_HEAD
      custom-model-data: 0
      item-model: ""     # opcjonalnie, jeżeli runtime udostępnia komponent item_model
      player-name: ""
      texture-url: ""
      texture-hash: ""
      texture-base64: ""
```

`command` może zawierać `{player}` lub `%player%`.

## Resource pack / custom item

Najbezpieczniejsza dla obecnego stosu jest para:

```yaml
icon:
  material: PAPER
  custom-model-data: 12345
```

HexGUI obsługuje też opcjonalne `item-model` przez kompatybilne wywołanie refleksyjne, więc brak tej funkcji w starszym API nie wyłącza pluginu.

## Własne główki

Dla `PLAYER_HEAD` można użyć jednego z:

- `player-name`,
- `texture-url`,
- `texture-hash`,
- `texture-base64`.

Jeżeli tekstura zostanie podana przy innym materiale, HexGUI automatycznie użyje `PLAYER_HEAD` i zapisze ostrzeżenie w konsoli.

## Puste sloty

Domyślnie wszystkie nieużywane sloty są wypełniane `BLACK_STAINED_GLASS_PANE`. Tooltip jest ukrywany (`hide-tooltip: true`), więc po najechaniu nie pojawia się nazwa ani opis.

## Domyślne pozycje

- Miasto → `/town`
- Miniony → `/minion list`
- Wiki minionów → `/minion wiki`
- Daily Questy → `/daily` (HexQuests, `hexquests.daily`)
- Codzienna nagroda → `/dailyrewards`
- Wiki maszyn/elektroniki → `/minion wiki electronics`
- Kolekcje → `/towncollections`

Od wersji 1.0.1 istniejący `config.yml` w wersji 1 jest automatycznie migrowany do `config-version: 2`; dodawane są tylko brakujące sekcje `entries.daily` i `entries.daily-rewards`, bez nadpisywania istniejących wpisów użytkownika.
