# HexCore - UI module guide

Ten plik opisuje, jak dziala warstwa UI w `HexCore` i jak jej uzywac w innych pluginach.

## Po co jest UI w HexCore

`UiService` daje wspolny sposob renderowania i wysylania wiadomosci opartych o MiniMessage:
- ten sam styl we wszystkich pluginach,
- template z `ui.yml`,
- tokeny (`<player>`, `<seconds>`, itd.),
- mniej duplikacji kodu.

Dzieki temu np. `Minigames`, `WaterDrawn` i inne pluginy moga miec rozne treści, ale wspolny silnik UI.

## Konfiguracja `ui.yml`

Plik: `Plugins/HexCore/src/main/resources/ui.yml`

Najwazniejsze sekcje:
- `prefix` - globalny prefix dla wiadomosci chat,
- `prefixes` - prefix per namespace (np. `elimination`, `drawn`),
- `overrides` - nadpisania templatek bez zmiany kodu pluginu,
- `presets` - zlozone akcje UI (chat/actionbar/title/sound),
- `templates` - szablony MiniMessage,
- `templateArgs` - mapowanie argumentow pozycyjnych dla `render(key, args...)`.

Kolejnosc rozwiazywania templatek:
1. `overrides` z pliku,
2. defaults zarejestrowane w runtime przez plugin (`registerDefaults`),
3. `templates` z `ui.yml`.

## Komenda testowa w HexCore

Komenda:
- `/uitpl <all|gracz> <template> [args...]`

Uprawnienie:
- `hexcore.ui.send`

Przyklady:

```text
/uitpl all core.echo Witaj na serwerze
/uitpl all core.minigame.score Steve 15 30
/uitpl Havix lobby.actionbar.countdown SkyWars 10 4 12
```

## Uzycie w innym pluginie (integracja)

### 1) `plugin.yml` zalezność

Najbezpieczniej dodac:

```yaml
depend:
  - HexCore
```

Mozesz tez uzyc `softdepend`, jesli plugin ma dzialac rowniez bez HexCore.

### 2) Pobranie `HexApi` przez ServicesManager

```java
var provider = Bukkit.getServicesManager().getRegistration(HexApi.class);
if (provider == null) {
    getLogger().severe("HexCore not found");
    getServer().getPluginManager().disablePlugin(this);
    return;
}

HexApi api = provider.getProvider();
```

### 3) Podstawowe wysylanie wiadomosci

```java
api.ui().send(player, "core.echo", "Witaj na serwerze");
api.ui().broadcast("core.reload.ok", "ui");
```

### 4) Tokeny nazwane (`UiTokens`)

```java
api.ui().send(
    player,
    "lobby.actionbar.waiting",
    UiTokens.of("game", "SkyWars")
        .put("current", "3")
        .put("max", "12")
        .put("min", "2")
);
```

### 5) Actionbar / Title / Sound

```java
api.ui().sendActionBar(player, "lobby.actionbar.countdown",
    UiTokens.of("game", "Spleef")
        .put("seconds", "5")
        .put("current", "8")
        .put("max", "12"));

api.ui().sendTitle(player,
    "test.message",
    "lobby.countdown.subtitle",
    UiTokens.of("seconds", "5"));

api.ui().playSound(player, Sound.ENTITY_PLAYER_LEVELUP);
```

## Rejestrowanie templatek przez plugin (namespace)

Dla modularnosci plugin moze dodac swoje domyslne template:

```java
api.ui().registerDefaults("elimination", Map.of(
    "kill.announce", "<red><victim></red> <gray>zostal wyeliminowany</gray>",
    "resurect.ok", "<green>Wskrzeszono: <player></green>"
));
```

Wtedy klucze sa widziane jako:
- `elimination.kill.announce`
- `elimination.resurect.ok`

Wersja z deklaracja argumentow:

```java
api.ui().registerDefaultsWithArgs("elimination", Map.of(
    "kill.announce", TemplateDefinition.of(
        "<red><victim></red> <gray>zostal wyeliminowany</gray>",
        List.of("victim")
    )
));
```

## Presety (zlozone akcje)

Preset mozna zarejestrowac w kodzie:

```java
api.ui().registerPreset("elimination.kill", UiPreset.builder()
    .broadcastChat("elimination.kill.announce")
    .sound("ENTITY_LIGHTNING_BOLT_THUNDER")
    .broadcastSound("ENTITY_LIGHTNING_BOLT_THUNDER")
    .build());
```

i uzyc:

```java
api.ui().broadcastPreset("elimination.kill", UiTokens.of("victim", player.getName()));
```

Mozesz tez definiowac presety w `ui.yml` sekcji `presets`.

## Jak projektowac napisy w zewnetrznych pluginach

### Problem

Obecnie pluginy rozmieszczaja teksty na 3 sposoby:

| Sposob | Przyklad | Wada |
|---|---|---|
| Hardcode w Javie | `sender.sendMessage("§aPrzeladowano")` | Admin nie zmieni tekstu |
| Sekcja `messages` w `config.yml` pluginu | WaterDrawn, HexElimination | Kazdy plugin robi to inaczej, &-kody, brak MiniMessage |
| HexCore `ui.yml` | Minigames lobby | Centralny plik, ale trzeba pamietac o kluczach |

### Rekomendacja: 3 warstwy

**1. Domyslne szablony rejestruj w kodzie pluginu (registerDefaults)**

Na starcie pluginu podaj swoje szablony w MiniMessage.
Admin nie musi nic robic - dzialaja od razu. Jesli chce zmienic,
nadpisuje jednym wpisem w `ui.yml` > `overrides`.

```java
// WaterDrawnPlugin.onEnable()
api.ui().registerDefaults("drawn", Map.of(
    "warning.immediate", "<red><bold>Natychmiast wyjdz z wody!</bold></red>",
    "warning.countdown",  "<red>Topisz sie! <white><seconds>s</white> do utoniecia</red>",
    "death",              "<red><player> utonal.</red>"
));
```

**2. W configu pluginu trzymaj TYLKO parametry logiki (nie tekst)**

```yaml
# config.yml pluginu WaterDrawn
drown-seconds: 5
check-interval-ticks: 5
drown-damage: 999.0
# NIE trzymaj tutaj sekcji messages:
```

Tekst trzymasz w HexCore przez registerDefaults.
Jesli admin chce go zmienic, pisze w `ui.yml`:

```yaml
overrides:
  drawn.warning.immediate: "<dark_red>Uciekaj z wody natychmiast!</dark_red>"
```

**3. W kodzie pluginu uzywaj `api.ui()` zamiast sendMessage**

Przed (stary styl):

```java
// WaterDrawn - stary styl
String msg = config.getWarningCountdownActionbar().replace("%seconds%", String.valueOf(sec));
player.sendActionBar(msg);
```

Po (nowy styl):

```java
// WaterDrawn - nowy styl
api.ui().sendActionBar(player, "drawn.warning.countdown",
    UiTokens.of("seconds", String.valueOf(sec)));
```

### Przyklad migracji HexElimination — krok po kroku

#### Jak bylo wczesniej (stary styl)

Kazdy plugin mial swoje teksty w `config.yml` z `&` kodami kolorow i `%placeholderami%`:

```yaml
# config.yml pluginu HexElimination (STARY styl)
messages:
  eliminated: "&8[&cELIMINACJA&8] &fGracz &e%nick% &fzostal &c&lWYELIMINOWANY&f!"
  resurrected: "&8[&aWSKRZESZENIE&8] &fGracz &e%target% &fzostal wskrzeszony przez &b%by%&f."
```

A w kodzie Java ladowal to z configa i recznie podmenial `%nick%`:

```java
// Stary kod w HexElimination
String msg = getConfig().getString("messages.eliminated")
    .replace("&", "§")
    .replace("%nick%", player.getName());
Bukkit.broadcastMessage(msg);
```

Problem: kazdy plugin robi to inaczej, admin musi szukac configow w 5 folderach,
format `&8[&c` jest nieczytelny, a MiniMessage nie dziala.

---

#### Krok 1: Zarejestruj domyslne szablony na starcie pluginu

Zamiast trzymac tekst w `config.yml`, plugin rejestruje swoje szablony
w `onEnable()` przez `api.ui().registerDefaults(...)`.

Pierwszy argument to **namespace** — nazwa pluginu (np. `"elimination"`).
Drugi to mapa: **klucz szablonu** → **tresc w MiniMessage**.

```java
// HexEliminationPlugin.onEnable()
api.ui().registerDefaults("elimination", Map.of(
    "kill.announce",
        "<dark_gray>[<red>ELIMINACJA</red>]</dark_gray>"
        + " <white>Gracz</white> <yellow><victim></yellow>"
        + " <white>zostal</white> <red><bold>WYELIMINOWANY</bold></red><white>!</white>",

    "resurect.announce",
        "<dark_gray>[<green>WSKRZESZENIE</green>]</dark_gray>"
        + " <white>Gracz</white> <yellow><target></yellow>"
        + " <white>zostal wskrzeszony przez</white> <aqua><by></aqua><white>.</white>"
));
```

Co to robi:
- Tworzy dwa szablony: `elimination.kill.announce` i `elimination.resurect.announce`.
- `<victim>`, `<target>`, `<by>` to **tokeny** (zmienne) — wypelniasz je pozniej w kodzie.
- Nie potrzeba juz sekcji `messages:` w `config.yml` pluginu.
- Tekst jest w formacie MiniMessage — czytelniejszy niz `&8[&c`.

#### Krok 2: Uzyj szablonu w logice gry

Gdy gracz ginie, chcesz wyslac wiadomosc wszystkim. Zamiast `Bukkit.broadcastMessage(msg)`,
uzywasz `api.ui().broadcast(...)` i podajesz klucz + tokeny:

```java
// Gracz zostal wyeliminowany — wyslij do wszystkich
api.ui().broadcast(
    "elimination.kill.announce",              // klucz szablonu
    UiTokens.of("victim", player.getName())   // token <victim> = nick gracza
);
```

HexCore:
1. Szuka szablonu `elimination.kill.announce` (patrz: kolejnosc rozwiazywania).
2. Podmienia `<victim>` na nick gracza.
3. Renderuje MiniMessage do ladnego kolorowego tekstu.
4. Wysyla do wszystkich graczy online.

Inny przyklad — wskrzeszenie (2 tokeny):

```java
// Admin wskrzesza gracza
api.ui().broadcast(
    "elimination.resurect.announce",
    UiTokens.of("target", target.getName())   // token <target>
             .put("by", admin.getName())       // token <by>
);
```

#### Krok 3: Admin chce zmienic tekst — bez dotykania kodu

Admin otwiera `ui.yml` w HexCore i dodaje wpis w sekcji `overrides`:

```yaml
# ui.yml (HexCore) — admin nadpisuje tresc szablonu
overrides:
  elimination.kill.announce: "<red>☠ <yellow><victim></yellow> odpadl z gry!</red>"
```

Od teraz ten tekst jest uzywany zamiast domyslnego z kodu.
Plugin nie wymaga zmian ani restartu — wystarczy `/hexcore reload`.

#### Dlaczego to lepsze?

| Cecha | Stary styl | Nowy styl |
|---|---|---|
| Gdzie tekst | `config.yml` kazdego pluginu | Kod pluginu (default) + `ui.yml` (override) |
| Format kolorow | `&8[&c` | `<red>`, `<bold>` (MiniMessage) |
| Zmienne | `%nick%` (recznie replace) | `<victim>` (automatycznie przez UiTokens) |
| Admin chce zmienic | Szuka configa pluginu | Jeden plik `ui.yml` > `overrides` |
| Prefix | Kazdy plugin robi swoj | Wspolny z HexCore (albo per namespace) |
| Actionbar/Title | Osobny kod | `api.ui().sendActionBar(...)` |

### Kiedy NIE uzywac HexCore UI?

- Wewnetrzne logi/debug (`getLogger().info(...)`) - to nie idzie do gracza.
- Jednorazowe, techniczne komunikaty admina (`§cNieznana komenda`).
- Plugin nie zalezy od HexCore (softdepend + fallback na zwykly sendMessage).

### Podsumowanie zasad

| Rodzaj tekstu | Gdzie trzymac | Format |
|---|---|---|
| Wiadomosci do gracza | `registerDefaults(namespace, ...)` | MiniMessage |
| Parametry logiki | `config.yml` pluginu | YAML wartosci |
| Nadpisanie tekstu przez admina | `ui.yml` > `overrides` | MiniMessage |
| Presety (chat+title+sound) | `registerPreset(...)` lub `ui.yml` > `presets` | MiniMessage + Sound enum |
| Debug/logi serwera | `getLogger()` | dowolny |

## Dobre praktyki

- Uzywaj namespace per plugin (`elimination.*`, `drawn.*`, `iceberg.*`).
- Trzymaj klucze stabilne; zmieniaj tresc przez `overrides`.
- Do dynamicznych wartosci preferuj `UiTokens` zamiast skladania stringow.
- `render(...)` wykorzystuj tam, gdzie sam kontrolujesz sposob wysylki (np. niestandardowe title/bossbar).
- Nie mieszaj `&` kodow z MiniMessage - uzywaj wylacznie MiniMessage.
- Dla testow admina uzywaj `/uitpl`.

## Szybka checklista dla nowego pluginu

1. Dodaj `depend`/`softdepend` na `HexCore`.
2. Pobierz `HexApi` z `ServicesManager` w `onEnable()`.
3. Zarejestruj domyslne szablony namespace przez `registerDefaults(...)`.
4. W configu pluginu trzymaj tylko parametry logiki, nie teksty.
5. Wysylaj komunikaty przez `api.ui()` zamiast `sendMessage("§...")`.
6. W razie potrzeby dodaj admin override do `ui.yml` w HexCore.

