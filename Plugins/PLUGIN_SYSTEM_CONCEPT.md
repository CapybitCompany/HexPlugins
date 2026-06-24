# HexPlugins — koncepcja architektury systemu pluginów SMP

> Dokument projektowy dla ekosystemu pluginów `HexPlugins`.
>
> Cel: zbudować elastyczny, czysty architektonicznie system, w którym `HexCore` jest wspólnym rdzeniem,
> `HexTowns` jest centralnym kontekstem progresji gracza na trybie SMP, a kolejne moduły — questy,
> daily questy, skille, statystyki, miniony, kolekcje, custom itemy, custom moby i mechaniki eventowe — można
> dokładać głównie przez konfigurację oraz małe, izolowane pluginy bez ingerowania w istniejący kod.

---

## 1. Założenia nadrzędne

System powinien być projektowany według kilku prostych zasad:

1. **`HexCore` jest kernelem, nie pluginem gameplayowym.**
   - Dostarcza wspólne API, bazę danych, UI, configi, feature flagi, message bus, registry usług i narzędzia techniczne.
   - Nie zna domeny minionów, questów, skilli, kolekcji ani miast poza bardzo ogólnymi kontraktami.

2. **`HexTowns` jest głównym kontekstem progresji SMP.**
   - Miasto jest bazowym „profilem progresji” gracza lub grupy COOP.
   - Większość danych gameplayowych powinna być przypisana do `townId`, a dopiero pomocniczo do `playerUuid`.
   - Usunięcie miasta jest granicą lifecycle danych: dane zależne od miasta muszą zostać wyczyszczone lub zarchiwizowane.

3. **Pluginy domenowe są niezależnymi modułami.**
   - `HexMinions`, `HexSkills`, `HexQuests`, `HexCollections`, `HexItems`, `HexMobs` itd. nie powinny być sklejone ze sobą bezpośrednio.
   - Komunikują się przez publiczne API, `ServicesManager`, eventy Bukkit i `HexMessageBus`.

4. **Nowa zawartość ma być dodawana configiem.**
   - Nowe daily questy, misje fabularne, miniony, kolekcje, statystyki, custom itemy, dropy i wymagania powinny być definicjami YAML.
   - Kod powinien implementować silniki i typy warunków/akcji, a nie konkretne questy czy konkretne itemy.

5. **Kod ma dodawać możliwości, config ma dodawać content.**
   - Jeżeli dokładamy nowy typ warunku, triggera albo rewarda — piszemy kod raz.
   - Jeżeli dokładamy nową misję opartą o istniejące warunki i rewardy — edytujemy tylko YAML.

6. **Brak exploitów przez usuwanie i tworzenie miasta.**
   - Statystyki, skille, kolekcje, miniony, quest progress i limity powiązane z miastem muszą być resetowane przy `town.destroy`.
   - Dane gracza, które mają przetrwać niezależnie od miasta, muszą być jawnie oznaczone jako globalne.

---

## 2. Warstwy systemu

```text
                   ┌─────────────────────────────────────┐
                   │          Website / Stats API         │
                   │        odczyt rankingów przez HTTP   │
                   └─────────────────────────────────────┘
                                      ▲
                                      │ SQL / read model
                                      │
┌──────────────────────────────────────────────────────────────────────┐
│                         Minecraft / Paper Server                      │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                            HexCore                             │  │
│  │  HexApi, DB, UI, Config, FeatureFlags, MessageBus, Registries  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                 ▲                     ▲                 ▲            │
│                 │ ServicesManager      │ MessageBus       │ UI/DB      │
│                 │                     │                 │            │
│  ┌─────────────────────────┐   ┌──────────────────────────────────┐  │
│  │        HexTowns          │   │       Moduły domenowe SMP         │  │
│  │  townId, claims, COOP,   │◄──┤ Skills, Quests, Minions, Items,   │  │
│  │  lifecycle miasta, meta  │   │ Collections, Mobs, Events, etc.   │  │
│  └─────────────────────────┘   └──────────────────────────────────┘  │
│                 ▲                     ▲                 ▲            │
│                 │ TownsApi             │ Domain APIs      │ Events     │
│                 └─────────────────────┴─────────────────┘            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Odpowiedzialność pluginów

### 3.1 `HexCore`

`HexCore` powinien być jedynym twardym fundamentem ekosystemu.

Odpowiada za:

- `HexApi` jako główny punkt wejścia dla innych pluginów,
- `DatabaseService` i wspólną konfigurację połączenia z bazą,
- `UiService` i centralne szablony wiadomości,
- `FeatureFlagService`,
- `RegionService`,
- `HexMessageBus`,
- generyczne rejestry definicji,
- narzędzia do walidacji configów,
- wspólne typy danych: `NamespacedKey`, `DefinitionId`, `ProgressScope`, `Requirement`, `Reward`, `Trigger` — docelowo jako API.

`HexCore` **nie powinien**:

- znać konkretnych minionów,
- znać konkretnych questów,
- kasować danych pluginów domenowych,
- zawierać logiki miasta,
- zawierać logiki skilli.

Rekomendacja zależności:

```yaml
# plugin.yml każdego pluginu SMP
 depend:
   - HexCore
```

### 3.2 `HexTowns`

`HexTowns` jest centralnym kontekstem trybu SMP.

Odpowiada za:

- tworzenie miasta,
- członkostwo owner/COOP,
- claimy i ochronę chunków,
- lifecycle miasta,
- `TownsApi`,
- `TownDataNamespace`,
- eventy Bukkit `TownCreatedEvent`, `TownDestroyedEvent`, `TownCoopJoinedEvent`, itd.,
- message bus kanały `towns.*`,
- meta dane miasta przez `getMeta` / `setMeta`,
- kontrolowane czyszczenie danych zależnych od miasta.

`HexTowns` **nie powinien** bezpośrednio kasować tabel `HexSkills`, `HexMinions`, `HexQuests` itd.
Zamiast tego:

1. Publikuje event domenowy, np. `TownDestroyedEvent`.
2. Publikuje wiadomość `towns.reset.requested`.
3. Publikuje wiadomość `towns.data.purge`.
4. Wywołuje zarejestrowane handlery `TownDataNamespace`.

### 3.3 `HexStats` / statystyki gameplayowe

Statystyki gracza powinny być rozdzielone na minimum dwa typy:

Ważne doprecyzowanie: **pluginy gameplayowe nie powinny chodzić po HTTP/HTML po własne dane**.
`StatsApi` jest osobnym backendem HTTP głównie dla strony WWW i publicznych rankingów read-only.
Na serwerze Minecraft źródłem zapisu i odczytu runtime pozostaje SQL przez `HexCore` / `DatabaseService`.
`HexSkills`, `HexQuests`, `HexCollections` i podobne pluginy zapisują progress do swoich tabel SQL.
Jeżeli kiedyś skala urośnie do tysięcy graczy online i oddzielna baza/read-model będzie potrzebna,
można zmigrować konkretne tabele lub dodać replikację bez zmiany kontraktów pluginów.

| Typ statystyki | Scope | Reset przy usunięciu miasta? | Przykład |
|---|---:|---:|---|
| Globalna konta | `GLOBAL_PLAYER` | Nie | pierwszy login, lifetime votes, zakupione rangi |
| Sezonowa gracza | `SEASON_PLAYER` | Zależnie od sezonu | ranking sezonowy PvP |
| Miejska/gracza w mieście | `TOWN_PLAYER` | Tak | mining level w aktualnym mieście, quest progress |
| Miejska wspólna | `TOWN` | Tak | kolekcje miasta, miniony, town perks |

Najbezpieczniejszy model dla SMP:

- progres skilli i questów: `town_id + player_uuid`,
- kolekcje i miniony: głównie `town_id`,
- ekonomia prywatna: zależnie od decyzji gameplayowej, ale jeśli ma być anty-exploitowa, też powinna mieć scope `town_id + player_uuid`,
- rzeczy premium/ranga: `player_uuid`, bez resetu.

### 3.4 `HexSkills`

`HexSkills` powinien być silnikiem skilli, a nie listą hardcodowanych skilli.

Odpowiada za:

- rejestr definicji skilli z YAML,
- naliczanie XP na podstawie triggerów,
- poziomy, progi i bonusy,
- API do odczytu poziomu skilla,
- publikowanie eventów typu `skills.xp.gained`, `skills.level.up`,
- czyszczenie danych po `towns.reset.requested` albo `towns.data.purge`.

Przykładowe definicje:

```yaml
skills:
  mining:
    display-name: "Kopanie"
    scope: TOWN_PLAYER
    max-level: 100
    xp-curve: exponential_default
    xp-sources:
      - trigger: minecraft.block.break
        filters:
          materials: [STONE, DEEPSLATE, DIAMOND_ORE]
        xp: 1
      - trigger: custom.item.use
        filters:
          item-id: hex:miners_scroll
        xp: 50
    rewards:
      - at-level: 10
        actions:
          - type: stat.modifier.add
            stat: mining_speed
            value: 0.05
```

### 3.5 `HexQuests`

`HexQuests` powinien obsługiwać:

- daily questy losowane z puli,
- questy zwykłe,
- questy fabularne,
- warunki zależności,
- wymagania odblokowania,
- progress per gracz/per miasto,
- rewardy,
- reset dzienny/tygodniowy/sezonowy,
- integrację z każdym systemem przez trigger bus.

Najważniejsza zasada: quest nie powinien znać pluginu minionów, custom itemów czy custom mobów przez kod.
Quest powinien znać **trigger stringowy** i dane eventu.

Przykładowo:

```yaml
quests:
  daily_miner_01:
    type: DAILY
    pool: mining_easy
    weight: 100
    scope: TOWN_PLAYER
    title: "Górnicza rozgrzewka"
    requirements:
      - type: skill.level.min
        skill: mining
        level: 3
    objectives:
      - id: break_stone
        trigger: minecraft.block.break
        filters:
          material: STONE
        amount: 128
    rewards:
      - type: coins.add
        amount: 250
      - type: skill.xp.add
        skill: mining
        amount: 100

  daily_custom_mob_01:
    type: DAILY
    pool: combat_medium
    weight: 20
    scope: TOWN_PLAYER
    title: "Polowanie na Lodowe Bestie"
    requirements:
      - type: plugin.enabled
        plugin: HexMobs
      - type: collection.unlocked
        collection: ice_creatures
    objectives:
      - id: kill_ice_beasts
        trigger: custom.mob.kill
        filters:
          mob-id: hex:ice_beast
        amount: 10
    rewards:
      - type: custom.item.give
        item-id: hex:frozen_core
        amount: 1
```

### 3.6 `HexMinions`

`HexMinions` powinien być pluginem zależnym od `HexTowns`, bo miniony są przypisane do miasta.

Odpowiada za:

- definicje minionów z YAML,
- stawianie miniona tylko na terenie miasta,
- limity per miasto,
- storage,
- upgrade'y,
- offline catch-up,
- czyszczenie danych po usunięciu miasta,
- publikowanie triggerów dla questów/kolekcji/skilli.

Przykładowe eventy/trigger messages:

```text
minions.placed
minions.picked_up
minions.resource.generated
minions.resource.claimed
minions.upgraded
```

### 3.7 `HexCollections`

Kolekcje powinny być generycznym systemem postępu opartym o trigger + filtr + licznik.

Przykładowa definicja:

```yaml
collections:
  cobblestone:
    display-name: "Bruk"
    scope: TOWN
    sources:
      - trigger: minecraft.block.break
        filters:
          material: COBBLESTONE
      - trigger: minions.resource.generated
        filters:
          resource-id: minecraft:cobblestone
    tiers:
      - amount: 100
        rewards:
          - type: recipe.unlock
            recipe-id: hex:cobble_generator
      - amount: 1000
        rewards:
          - type: town.meta.add_int
            key: minions.limit_bonus
            amount: 1
```

### 3.8 `HexItems` i `HexMobs`

Custom itemy i custom moby powinny być providerami definicji oraz źródłami triggerów.

`HexItems`:

- rejestruje custom itemy,
- udostępnia `CustomItemApi`,
- publikuje `custom.item.use`, `custom.item.craft`, `custom.item.consume`,
- pozwala innym pluginom sprawdzić `item-id` bez importowania klas.

`HexMobs`:

- rejestruje custom moby,
- publikuje `custom.mob.spawn`, `custom.mob.kill`, `custom.mob.damage`,
- pozwala questom filtrować po `mob-id`.

---

## 4. Zasady zależności między pluginami

### 4.1 Poziomy zależności

| Poziom | Mechanizm | Kiedy używać |
|---|---|---|
| Twarda zależność | `depend` + `compileOnly project(...)` | Gdy plugin nie ma sensu bez drugiego, np. `HexMinions` bez `HexTowns` |
| Miękka zależność | `softdepend` + optional lookup | Gdy integracja jest dodatkowa |
| API runtime | `ServicesManager` | Gdy potrzebny jest odczyt danych lub wykonanie komendy domenowej |
| Event Bukkit | klasy eventów | Gdy plugin kompiluje się z API drugiego pluginu |
| `HexMessageBus` | string channel + `HexMessageData` | Gdy pluginy nie powinny znać swoich klas |
| Config registry | YAML definitions | Gdy dokładamy content bez kodu |

### 4.2 Reguła praktyczna

1. Każdy plugin zależy od `HexCore`.
2. Pluginy związane z miastem mogą zależeć od `HexTowns`.
3. Pluginy równorzędne nie powinny zależeć od siebie bezpośrednio.
4. Questy nie zależą od minionów, itemów ani mobów — questy słuchają triggerów.
5. Skille nie zależą od minionów — skille słuchają triggerów.
6. Kolekcje nie zależą od itemów — kolekcje słuchają triggerów i filtrują `resource-id` / `item-id`.

Przykład:

```text
HexQuests  NIE importuje HexMinions.
HexMinions publikuje: minions.resource.claimed {townId, playerUuid, resourceId, amount}
HexQuests ma w configu objective z triggerem minions.resource.claimed.
```

---

## 5. Wspólny model: definicje, triggery, wymagania, rewardy

Żeby system był generyczny, warto wprowadzić cztery podstawowe pojęcia.

### 5.1 Definition

Definicja to dowolny content ładowany z YAML.

Przykłady:

- quest definition,
- skill definition,
- collection definition,
- minion type definition,
- custom item definition,
- custom mob definition,
- loot table definition,
- reward table definition.

Każda definicja powinna mieć:

```yaml
id: hex:mining_daily_01
schema-version: 1
enabled: true
conditions: []
tags: []
```

Rekomendowany format ID:

```text
namespace:path
```

Przykłady:

```text
hex:mining_daily_01
minecraft:diamond
minions:cobblestone_tier_1
mobs:ice_beast
```

### 5.2 Trigger

Trigger to zdarzenie gameplayowe zapisane jako string.

Przykłady:

```text
minecraft.block.break
minecraft.entity.kill
minecraft.item.craft
custom.item.use
custom.mob.kill
minions.resource.generated
minions.resource.claimed
skills.level.up
towns.created
towns.destroyed
```

Każdy trigger powinien publikować standardowy envelope:

```yaml
trigger: minions.resource.claimed
source-plugin: HexMinions
occurred-at: 2026-05-25T12:00:00Z
actor:
  playerUuid: "..."
context:
  townId: "..."
data:
  resource-id: minecraft:cobblestone
  amount: 64
```

W Javie obecny `HexMessageData` może pełnić tę rolę bez zależności klasowych.

### 5.3 Requirement

Requirement odpowiada na pytanie: „czy ta definicja może być użyta?”

Przykłady typów:

```text
plugin.enabled
feature.enabled
town.exists
town.level.min
town.member.count.min
skill.level.min
collection.unlocked
permission.has
world.allowed
quest.completed
quest.not_completed
random.chance
server.date_between
```

Przykładowy YAML:

```yaml
requirements:
  - type: skill.level.min
    skill: mining
    level: 10
  - type: collection.unlocked
    collection: cobblestone
  - type: plugin.enabled
    plugin: HexMinions
```

### 5.4 Reward / Action

Reward to akcja wykonywana po spełnieniu warunku.

Przykłady:

```text
coins.add
ranking_points.add
skill.xp.add
custom.item.give
minecraft.item.give
collection.progress.add
town.growth.add
town.meta.set
town.meta.add_int
command.console
permission.grant_temp
quest.start
```

Przykład:

```yaml
rewards:
  - type: coins.add
    amount: 500
  - type: town.growth.add
    amount: 1
    source: quest:daily_miner_01
  - type: custom.item.give
    item-id: hex:miners_box
    amount: 1
```

---

## 6. Scope danych i reset anty-exploitowy

Najważniejszy element architektury: każda tabela i każdy progress muszą mieć jawnie określony scope.

### 6.1 Zalecane scope'y

```java
public enum ProgressScope {
    GLOBAL_PLAYER,  // nie kasujemy przy town destroy
    SEASON_PLAYER,  // kasujemy przy resecie sezonu
    TOWN,           // kasujemy przy town destroy
    TOWN_PLAYER     // kasujemy przy town destroy lub opuszczeniu COOP
}
```

### 6.2 Zasady resetu

| Operacja | Co czyścić |
|---|---|
| `/town destroy` | Wszystko w scope `TOWN` i `TOWN_PLAYER` dla danego `townId` |
| `/town endcoop` | `TOWN_PLAYER` dla danego `playerUuid` w danym `townId`; dane `TOWN` zostają |
| Kick z COOP | Tak samo jak `endcoop`, zależnie od zasad gameplayowych |
| Reset sezonu | `SEASON_PLAYER`, opcjonalnie też wszystkie miasta |
| Ban / wipe admina | Według osobnej komendy administracyjnej |

### 6.3 Wymagany kontrakt pluginów domenowych

Każdy plugin przechowujący dane miasta powinien przy starcie rejestrować namespace:

```java
townsApi.dataNamespace(this, "skills", (townId, members) -> {
    // usuń dane skills dla townId i members
    return CompletableFuture.completedFuture(null);
});
```

Namespace'y:

```text
skills
quests
minions
collections
items
economy
mobs
```

Przy `town.destroy` `HexTowns` powinien:

1. Oznaczyć miasto jako `DESTROYING`.
2. Zablokować dalsze mutacje danych miasta.
3. Opublikować `towns.destroyed`.
4. Opublikować `towns.reset.requested`.
5. Opublikować `towns.data.purge`.
6. Wywołać wszystkie handlery `TownDataNamespace`.
7. Dopiero po sukcesie usunąć claimy, członków i miasto albo oznaczyć je jako `DELETED`.

### 6.4 Transaction outbox dla bezpieczeństwa

Docelowo warto dodać mechanizm `outbox`, żeby reset danych był odporny na crash serwera.

Tabela przykładowa:

```sql
CREATE TABLE hex_outbox_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP NULL
);
```

Dla `town.destroy`:

```text
TOWN_DATA_PURGE_REQUESTED townId=...
TOWN_PLAYER_RESET_REQUESTED townId=..., members=[...]
```

Worker w `HexCore` lub `HexTowns` ponawia nieprzetworzone eventy po restarcie.

---

## 7. System daily questów i questów fabularnych

### 7.1 Cel

Daily questy mają być losowane z puli i zależeć od dowolnych systemów:

- vanilla akcje,
- custom itemy,
- custom moby,
- miniony,
- kolekcje,
- skille,
- miasto,
- eventy serwerowe,
- mechaniki innych pluginów.

Nie robimy osobnej integracji „quest z minionem” w kodzie.
Robimy jeden system triggerów i filtrów.

### 7.2 Pipeline daily questów

```text
Start dnia / login gracza
        │
        ▼
QuestPoolResolver wybiera pule dostępne dla gracza/miasta
        │
        ▼
RequirementEngine filtruje questy niemożliwe
        │
        ▼
WeightedRandom losuje questy
        │
        ▼
QuestProgressService tworzy progress TOWN_PLAYER
        │
        ▼
TriggerEngine aktualizuje objective po eventach
        │
        ▼
RewardEngine wypłaca nagrody
```

### 7.3 Definicja puli

```yaml
daily-pools:
  mining_easy:
    enabled: true
    slots: 1
    weight: 100
    requirements:
      - type: skill.level.max
        skill: mining
        level: 20
    include-tags: [mining, easy]
    exclude-tags: [disabled]

  mixed_midgame:
    enabled: true
    slots: 3
    weight: 50
    requirements:
      - type: town.exists
      - type: town.age.min_days
        days: 3
```

### 7.4 Quest fabularny

Quest fabularny powinien być grafem kroków.

```yaml
story-quests:
  tutorial_town_growth:
    title: "Rozwój pierwszego miasta"
    scope: TOWN_PLAYER
    auto-start:
      requirements:
        - type: town.exists
    steps:
      start:
        objectives:
          - id: claim_first_chunk
            trigger: towns.chunk.claimed
            filters:
              actor-is-player: true
            amount: 1
        next: place_minion

      place_minion:
        objectives:
          - id: place_any_minion
            trigger: minions.placed
            amount: 1
        next: collect_resources

      collect_resources:
        objectives:
          - id: collect_wood
            trigger: minions.resource.claimed
            filters:
              resource-id: minecraft:oak_log
            amount: 128
        rewards:
          - type: town.growth.add
            amount: 2
        complete: true
```

---

## 8. Generyczny Trigger Engine

### 8.1 Po co

Jeżeli questy, skille i kolekcje mają reagować na to samo zdarzenie, nie chcemy implementować tego trzy razy.

Przykład: gracz zabija custom moba.

To samo zdarzenie może:

- dodać progress questa,
- dodać XP skilla combat,
- dodać progress kolekcji `ice_creatures`,
- odblokować achievement,
- naliczyć ranking.

### 8.2 Koncepcja

W `HexCore` docelowo może powstać `TriggerService`:

```java
public interface TriggerService {
    void publish(GameTrigger trigger);
    void subscribe(String triggerId, TriggerListener listener);
}
```

Ale na start można używać istniejącego `HexMessageBus`:

```text
channel: "trigger.minecraft.block.break"
channel: "trigger.custom.mob.kill"
channel: "trigger.minions.resource.claimed"
```

albo bez prefiksu, jeśli obecna konwencja pluginów już to zakłada:

```text
minecraft.block.break
custom.mob.kill
minions.resource.claimed
```

Rekomendacja: używać prefiksu `trigger.*` dla generycznych zdarzeń gameplayowych, a `towns.*` zostawić jako domenowe eventy miast.

### 8.3 Standardowe pola triggera

```yaml
schema-version: 1
trigger-id: custom.mob.kill
source-plugin: HexMobs
actor:
  playerUuid: "..."
context:
  townId: "..."
  world: world
  x: 100
  y: 64
  z: -20
data:
  mob-id: hex:ice_beast
  level: 12
  biome: SNOWY_PLAINS
```

---

## 9. Registry contentu

### 9.1 Problem

Jeżeli każdy plugin ładuje YAML po swojemu, szybko powstaną różne formaty, różne walidacje i trudne integracje.

### 9.2 Rozwiązanie

Każdy plugin może mieć własne pliki YAML, ale powinien używać wspólnych konwencji:

```text
id
schema-version
enabled
display-name
description
tags
requirements
triggers/objectives
rewards/actions
```

Docelowo w `HexCore` warto mieć:

```java
public interface DefinitionRegistry<T> {
    void register(T definition);
    Optional<T> find(NamespacedId id);
    Collection<T> allEnabled();
    ValidationReport reload();
}
```

Oraz:

```java
public interface RequirementType {
    String type();
    boolean test(RequirementContext context, ConfigSection config);
}

public interface RewardType {
    String type();
    CompletableFuture<Void> execute(RewardContext context, ConfigSection config);
}

public interface FilterType {
    String type();
    boolean matches(TriggerContext context, ConfigSection config);
}
```

### 9.3 Plugin jako provider typów

Plugin może rejestrować nowe typy requirementów/rewardów/filterów.

Przykład: `HexSkills` rejestruje:

```text
requirement: skill.level.min
reward: skill.xp.add
filter: skill.id
```

`HexTowns` rejestruje:

```text
requirement: town.exists
requirement: town.member.count.min
reward: town.growth.add
reward: town.meta.set
```

`HexItems` rejestruje:

```text
requirement: custom.item.has
reward: custom.item.give
filter: item-id
```

Dzięki temu `HexQuests` nie musi znać klas tych pluginów — korzysta z typów zarejestrowanych w runtime.

---

## 10. Baza danych — konwencje

### 10.1 Każdy plugin ma własne tabele

Przykład:

```text
hex_towns
hex_town_members
hex_town_chunks
hex_skills_progress
hex_quests_progress
hex_minions
hex_collections_progress
```

### 10.2 Tabele progresu powinny mieć scope

Przykład `HexSkills`:

```sql
CREATE TABLE hex_skills_progress (
  town_id BINARY(16) NULL,
  player_uuid BINARY(16) NOT NULL,
  skill_id VARCHAR(64) NOT NULL,
  xp BIGINT NOT NULL DEFAULT 0,
  level INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (town_id, player_uuid, skill_id),
  INDEX idx_player (player_uuid),
  INDEX idx_town (town_id)
);
```

Przykład `HexQuests`:

```sql
CREATE TABLE hex_quests_progress (
  town_id BINARY(16) NULL,
  player_uuid BINARY(16) NOT NULL,
  quest_id VARCHAR(128) NOT NULL,
  quest_type VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  progress_json JSON NOT NULL,
  assigned_for_date DATE NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (town_id, player_uuid, quest_id, assigned_for_date),
  INDEX idx_town_player (town_id, player_uuid)
);
```

### 10.3 Reset po mieście

Każdy plugin powinien mieć metodę typu:

```sql
DELETE FROM hex_skills_progress WHERE town_id = ?;
DELETE FROM hex_quests_progress WHERE town_id = ?;
DELETE FROM hex_collections_progress WHERE town_id = ?;
DELETE FROM hex_minions WHERE town_id = ?;
```

Dla `endcoop`:

```sql
DELETE FROM hex_skills_progress WHERE town_id = ? AND player_uuid = ?;
DELETE FROM hex_quests_progress WHERE town_id = ? AND player_uuid = ?;
```

---

## 11. Konfiguracje — docelowy układ

Proponowany układ plików:

```text
Plugins/
  HexCore/
    config.yml
    ui.yml
    feature-flags.yml
    registries.yml

  HexTowns/
    config.yml
    limits.yml
    town-levels.yml

  HexSkills/
    config.yml
    skills.yml
    xp-curves.yml

  HexQuests/
    config.yml
    daily-pools.yml
    quests/
      daily-mining.yml
      daily-combat.yml
      story-tutorial.yml

  HexMinions/
    config.yml
    minion-types.yml
    resources.yml
    upgrades.yml
    appearance.yml
    menus.yml
    limits.yml

  HexCollections/
    config.yml
    collections.yml

  HexItems/
    config.yml
    items.yml
    recipes.yml

  HexMobs/
    config.yml
    mobs.yml
    spawn-rules.yml
```

---

## 12. Przykładowy flow: gracz niszczy miasto

```text
Player: /town destroy confirm
        │
        ▼
HexTowns waliduje ownera i status miasta
        │
        ▼
HexTowns ustawia status DESTROYING
        │
        ▼
HexTowns publikuje TownDestroyedEvent + towns.destroyed
        │
        ▼
HexTowns publikuje towns.reset.requested {playerUuids, townId, reason=destroy}
        │
        ▼
HexTowns publikuje towns.data.purge {townId, namespaces}
        │
        ▼
HexSkills usuwa progress TOWN_PLAYER
HexQuests usuwa progress TOWN_PLAYER
HexMinions usuwa miniony TOWN
HexCollections usuwa kolekcje TOWN
HexItems usuwa storage/wiązaną zawartość TOWN, jeśli istnieje
        │
        ▼
HexTowns usuwa claimy, członków i miasto / oznacza jako DELETED
        │
        ▼
UI informuje gracza
```

### Ważna uwaga

Jeżeli któryś plugin nie wyczyści danych, powinniśmy mieć mechanizm wykrycia tego:

- `TownDataNamespace` zwraca `CompletableFuture<Void>`,
- `HexTowns` loguje sukces/porażkę każdego namespace,
- outbox ponawia nieudane purge,
- admin ma komendę diagnostyczną:

```text
/town admin purge-status <townId>
/town admin retry-purge <townId>
```

---

## 13. Przykładowy flow: quest reaguje na custom moba

```text
HexMobs wykrywa śmierć custom moba
        │
        ▼
HexMobs publikuje trigger.custom.mob.kill
        │
        ▼
HexQuests sprawdza aktywne objective gracza
        │
        ▼
FilterEngine porównuje mob-id z YAML
        │
        ▼
QuestProgressService zwiększa licznik
        │
        ▼
Jeżeli complete: RewardEngine wypłaca nagrody
        │
        ▼
HexQuests publikuje quests.completed
```

Quest YAML:

```yaml
objectives:
  - id: kill_ice_beasts
    trigger: custom.mob.kill
    filters:
      mob-id: hex:ice_beast
    amount: 10
```

Kod questa nie importuje `HexMobs`.

---

## 14. Przykładowy flow: minion generuje surowiec i wpływa na kolekcje/skille/questy

```text
HexMinions wykonuje offline catch-up
        │
        ▼
Generuje 320 cobblestone dla townId
        │
        ▼
Publikuje minions.resource.generated {townId, resource-id, amount}
        │
        ├── HexCollections nalicza kolekcję cobblestone dla miasta
        ├── HexQuests nalicza daily questa, jeśli objective pasuje
        └── HexSkills może naliczyć skill engineering/farming, jeśli config tak definiuje
```

---

## 15. Wersjonowanie kontraktów

Każdy publiczny kontrakt powinien mieć wersję.

### 15.1 Eventy message bus

W `HexMessageData` dodawać:

```text
schemaVersion: 1
```

Przykład:

```text
channel: towns.destroyed
schemaVersion: 1
townId: ...
ownerUuid: ...
members: [...]
reason: destroy
```

### 15.2 Configi

Każdy YAML z definicjami powinien mieć:

```yaml
schema-version: 1
```

albo per definicja:

```yaml
quests:
  daily_miner_01:
    schema-version: 1
```

### 15.3 Zmiana kontraktu

- Dodanie pola: OK, kompatybilne.
- Usunięcie pola: breaking change.
- Zmiana znaczenia pola: breaking change.
- Nowa wersja eventu: albo `schemaVersion: 2`, albo nowy channel, np. `towns.destroyed.v2`.

---

## 16. Proponowane moduły docelowe

```text
HexCore             // kernel
HexTowns            // miasta, claimy, COOP, lifecycle
HexStats            // statystyki gameplayowe in-game, write model
StatsApi            // HTTP read model dla WWW, nie runtime API dla pluginów gameplayowych
HexSkills           // skille config-driven
HexQuests           // daily/story/repeatable quest engine
HexCollections      // kolekcje town/player
HexMinions          // miniony miasta
HexItems            // custom item registry
HexMobs             // custom mob registry
HexLoot             // loot tables / reward tables, opcjonalnie część HexCore/HexItems
HexEconomy          // ekonomia, jeśli obecne coins z HexCore będą rozdzielane
HexAdmin            // diagnostyka, migracje, debug registry/outbox, opcjonalnie
```

---

## 17. Minimalny MVP architektury

Nie trzeba implementować wszystkiego naraz. Minimalna kolejność:

### Etap 1 — stabilizacja fundamentu

1. `HexCore`:
   - utrzymać `HexApi`, `DatabaseService`, `UiService`, `HexMessageBus`,
   - dodać konwencję triggerów `trigger.*`,
   - dodać podstawowe typy `NamespacedId` i walidatory YAML, jeśli potrzebne.

2. `HexTowns`:
   - utrzymać `TownsApi`,
   - utrzymać `TownDataNamespace`,
   - doprecyzować purge flow,
   - dodać logowanie per namespace.

### Etap 2 — content-driven systems

3. `HexSkills`:
   - YAML `skills.yml`,
   - trigger listener,
   - progress `TOWN_PLAYER` zapisywany w SQL przez `HexCore` / `DatabaseService`,
   - purge namespace `skills`.

   Status MVP w repo:
   - moduł `Plugins/HexSkills`,
   - `skills.yml`,
   - subskrypcja `TriggerService`,
   - tabela `skills_progress`,
   - namespace purge `skills`,
   - komenda `/hexskills reload|info`.

4. `HexQuests`:
   - YAML questów,
   - daily pool resolver,
   - objective trigger listener,
   - reward engine,
   - purge namespace `quests`.

   Status MVP w repo:
   - moduł `Plugins/HexQuests`,
   - `quests.yml` i `daily-pools.yml`,
   - deterministyczne ważone losowanie daily questów per `townId + playerUuid + dzień`,
   - tabele `quests_progress` i `quest_objective_progress`,
   - subskrypcja `TriggerService`,
   - reward MVP `town.growth.add` i `command.console`,
   - namespace purge `quests`,
   - komenda `/hexquests reload|info|daily`.

5. `HexCollections`:
   - YAML kolekcji,
   - trigger listener,
   - progress `TOWN`,
   - purge namespace `collections`.

   Status MVP w repo:
   - moduł `Plugins/HexCollections`,
   - `collections.yml`,
   - subskrypcja `TriggerService`,
   - tabela `collections_progress`,
   - namespace purge `collections`,
   - komenda `/hexcollections reload|info`.

### Etap 3 — providers

W aktualnej iteracji świadomie pomijamy `HexItems` i `HexMobs`. Priorytetem jest pełniejszy MVP
`HexCollections` oraz dopięcie `HexMinions` jako zaufanego producenta progresu kolekcji.

6. `HexItems`:
   - custom item IDs,
   - reward `custom.item.give`,
   - trigger `custom.item.use`.

   Status: pominięte w tej iteracji.

7. `HexMobs`:
   - custom mob IDs,
   - trigger `custom.mob.kill`.

   Status: pominięte w tej iteracji.

8. `HexMinions`:
   - definicje minionów,
   - trigger `minions.*`,
   - purge namespace `minions`.

   Status MVP w repo:
   - `HexMinions` ma `softdepend: [HexCollections]`,
   - przy odbiorze storage publikuje trigger `minions.resource.claimed`,
   - trigger zawiera `townId`, `playerUuid`, `resourceId`, `collectionId`, `amount`, `source=MINION_COLLECT`,
   - `resources.yml` używa nowych ID kolekcji `mining.cobblestone` i `mining.iron`,
   - dodany przykładowy `iron` resource i `iron` minion.

9. `HexCollections` — rozszerzenie MVP pod Hypixel-like collections:
   - publiczne `HexCollectionsApi`,
   - `CollectionProgressContext`, `CollectionAddResult`, `CollectionSource`,
   - kolekcje town/COOP, nie per gracz,
   - folder `collections/*.yml`,
   - domyślne `mining.cobblestone` i `mining.iron`, po 7 poziomów,
   - RAM cache per `townId`,
   - batch flush do SQL,
   - placeholdery PlaceholderAPI czytające z cache,
   - anty-exploit MVP: player-placed blocks + recently-broken cache + blokada claimów miasta dla natural block break,
   - `TownDataNamespace` i `deleteTownCollectionData(townId)`,
   - eventy: `CollectionProgressAddEvent`, `CollectionLevelUpEvent`, `TownCollectionResetEvent`.

---

## 18. Checklist dla każdego nowego pluginu SMP

Każdy nowy plugin powinien spełnić tę checklistę:

- [ ] Ma `depend: [HexCore]`.
- [ ] Jeśli dane są związane z miastem, ma `depend` albo bezpieczny runtime lookup `HexTowns`.
- [ ] Pobiera `HexApi` przez `ServicesManager`.
- [ ] Jeśli używa miasta, pobiera `TownsApi` przez `ServicesManager`.
- [ ] Rejestruje własny namespace UI przez `api.ui().registerDefaults(...)`.
- [ ] Rejestruje `TownDataNamespace`, jeśli przechowuje dane `TOWN` lub `TOWN_PLAYER`.
- [ ] Wszystkie definicje contentu trzyma w YAML.
- [ ] Każda definicja ma `id`, `enabled`, opcjonalnie `schema-version`.
- [ ] Nie importuje klas równorzędnych pluginów, jeśli wystarczy trigger/message bus.
- [ ] Publikuje generyczne triggery dla questów/skilli/kolekcji.
- [ ] Ma komendę `/plugin reload` walidującą config przed podmianą registry.
- [ ] Ma migracje DB albo `ensureTables()`.
- [ ] Ma test purge danych po `town.destroy`.

---

## 19. Decyzje architektoniczne

### 19.1 Miasto jako główny profil progresji

Rekomendacja: dla SMP przyjąć, że większość progresji gameplayowej jest zależna od miasta.

Plusy:

- brak exploitu przez tworzenie/usuwanie miasta,
- łatwe COOP,
- łatwe rankingi miast,
- łatwy wipe sezonowy,
- naturalny hub dla minionów i kolekcji.

Minusy:

- trzeba jasno rozdzielić rzeczy globalne od miejskich,
- przy opuszczeniu COOP trzeba świadomie zdecydować, co zostaje, a co znika.

### 19.2 Event bus jako integracja równorzędna

Pluginy równorzędne powinny mówić do siebie przez trigger/message bus, nie przez importy.

Przykład dobrej integracji:

```text
HexMinions -> trigger.minions.resource.claimed -> HexQuests / HexCollections / HexSkills
```

Przykład złej integracji:

```text
HexQuests importuje klasy HexMinions i sprawdza MinionType.COBBLESTONE
```

### 19.3 API tylko do odczytu i komend domenowych

Publiczne API pluginu powinno być używane wtedy, gdy potrzebny jest aktualny stan lub wykonanie operacji.

Przykład:

- `TownsApi#townIdOf(playerUuid)` — dobry przypadek API.
- `TownsApi#addGrowthPoints(...)` — dobra komenda domenowa.
- `HexQuests` pytające `HexMinions` o każdy event — zły przypadek, lepszy trigger.

---

## 20. Ryzyka i zabezpieczenia

| Ryzyko | Zabezpieczenie |
|---|---|
| Niepełny reset danych po town destroy | `TownDataNamespace`, outbox, retry, admin diagnostics |
| Zbyt dużo zależności między pluginami | `HexMessageBus`, trigger strings, registry typów |
| Config z błędami psuje runtime | walidacja przed reloadem, atomic registry swap |
| Quest zależy od pluginu, którego nie ma | requirement `plugin.enabled`, pomijanie definicji |
| Duży koszt eventów | indeks aktywnych objective po triggerze, nie skanować wszystkiego |
| Skanowanie wszystkich miast | API cursor/page, cache O(1), brak `getAllTowns()` hot-path |
| Breaking changes eventów | `schemaVersion`, kompatybilne dodawanie pól |
| Exploit przez COOP leave | reset `TOWN_PLAYER` przy `endcoop` |

---

## 21. Rekomendacja końcowa

Najczystszy kierunek dla `HexPlugins`:

1. **`HexCore` jako kernel techniczny.**
2. **`HexTowns` jako właściciel lifecycle miasta i scope'u progresji.**
3. **Wszystkie systemy contentowe jako silniki config-driven.**
4. **Integracja przez `ServicesManager` tylko tam, gdzie potrzebny jest stan lub komenda domenowa.**
5. **Integracja przez `HexMessageBus` / triggery tam, gdzie chodzi o reakcje gameplayowe.**
6. **Każdy plugin z danymi miasta musi rejestrować `TownDataNamespace`.**
7. **Daily questy, misje, skille i kolekcje powinny korzystać z tego samego modelu: trigger + filters + requirements + rewards.**

Dzięki temu dodanie nowego contentu wygląda docelowo tak:

- nowa misja: edycja YAML w `HexQuests`,
- nowy minion: edycja YAML w `HexMinions`,
- nowa kolekcja: edycja YAML w `HexCollections`,
- nowy custom item: edycja YAML w `HexItems`,
- nowy custom mob: edycja YAML w `HexMobs`,
- nowy typ warunku/rewarda/triggera: mały kod w odpowiednim pluginie + rejestracja typu.

To utrzymuje system elastyczny, modułowy i odporny na rozrost projektu.

