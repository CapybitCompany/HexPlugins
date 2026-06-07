# HexMinions — plan techniczny pluginu minionów SMP

> Plan wykonania dla agenta AI tworzącego nowy plugin **HexMinions** w monorepo `HexPlugins`.
> Plugin ma współpracować z `HexTowns`: miniony są przypisane do miasta, limity są per-miasto,
> a usunięcie miasta musi usuwać albo dezaktywować wszystkie dane minionów. Projekt ma być możliwie
> generyczny: łatwe dodawanie typów minionów, generowanych surowców, upgrade'ów, wyglądu i menu przez YAML.

---

## 0. Źródła i założenia

### 0.1 Źródła w repo

- `Plugins/HexTowns/PLAN.md` — HexTowns jest hubem danych miasta i eksportuje `TownsApi` przez `ServicesManager`.
- `Plugins/HexTowns/src/main/java/hex/towns/api/TownsApi.java` — publiczne API miasta.
- `Plugins/HexTowns/src/main/java/hex/towns/model/Town.java` — `Town#internalId()` jest technicznym `BIGINT` używanym w DB.
- `Plugins/DESIGN_EVENT_BUS.md` — konwencja `HexMessageBus` dla luźnej komunikacji między pluginami.

### 0.2 Założenia gameplay

- Minion jest obiektem stojącym w świecie, przypisanym do konkretnego miasta.
- Miasto ma konfigurowalny limit aktywnych minionów.
- Minion może generować jeden albo wiele zasobów według definicji typu, tieru, paliwa i upgrade'ów.
- Minion ma storage, poziom/tier, etykietę nad głową i menu po kliknięciu PPM.
- Wygląd miniona ma być w pełni konfigurowalny: armor standy, główki, bloki, zbroja, itemy w rękach, label.
- Gracze nie mogą zdejmować wyposażenia miniona ani ręcznie go modyfikować.
- System ma być inspirowany Hypixel SkyBlock Minions: tierowanie, czas akcji, storage, paliwa, ulepszenia,
  przyszłe kolekcje i custom itemy do upgrade'ów.

### 0.3 Decyzja: YAML zamiast JSON jako format główny

Rekomendowany format konfiguracji: **YAML**.

Uzasadnienie:

- Jest standardem w pluginach Paper/Spigot.
- Jest czytelniejszy dla adminów niż JSON.
- Pozwala używać komentarzy w plikach konfiguracyjnych.
- Łatwo mapuje się na `YamlConfiguration` Bukkit/Paper.
- JSON można dodać później jako alternatywny loader dla generatorów/edytorów webowych, ale nie jako format MVP.

---

## 1. Zakres MVP

W pierwszej iteracji implementujemy:

1. Moduł Gradle `Plugins/HexMinions`.
2. `plugin.yml` z `depend: [HexCore, HexTowns]`.
3. Pobranie `HexApi` i `TownsApi` przez `ServicesManager`.
4. Namespace danych miasta: `townsApi.dataNamespace(this, "minions", handler)`.
5. Komendy administracyjne i użytkowe:
   - `/minion give <player> <type> [tier] [amount]`
   - `/minion list`
   - `/minion pickup <id>` albo przycisk w GUI
   - `/minion move <id>` albo przycisk w GUI `Przenieś tutaj` — przenosi wybranego miniona w lokalizację, w której stoi gracz, jeśli nadal jest to obręb tego samego miasta
   - `/minion reload`
   - `/minion admin debug <id>`
6. Item do stawiania miniona z `PersistentDataContainer`.
7. Walidacja stawiania:
   - gracz musi należeć do miasta,
   - lokalizacja musi znajdować się w chunku tego miasta,
   - limit minionów miasta nie może być przekroczony,
   - obszar wokół miniona musi być wolny według konfiguracji typu.
8. Persistencja minionów w DB.
9. Spawn/despawn wizualizacji miniona jako zestaw entity kontrolowanych przez plugin.
10. Etykieta nad minionem: typ + poziom/tier + opcjonalnie stan storage.
11. Menu po PPM:
    - podgląd storage,
    - odbiór surowców,
    - upgrade poziomu,
    - pickup miniona,
    - przeniesienie miniona do pozycji gracza w obrębie miasta,
    - sloty paliwa/ulepszeń, jeśli typ je obsługuje.
12. Generator zasobów działający bez tickowania każdego miniona co tick.
13. Offline catch-up po restarcie/wyładowaniu chunka.
14. Konfiguracje YAML:
    - `config.yml`,
    - `minion-types.yml`,
    - `resources.yml`,
    - `upgrades.yml`,
    - `appearance.yml`,
    - `menus.yml`,
    - `limits.yml`.
15. Czyszczenie danych po `TownDestroyedEvent` / `towns.data.purge`.

Poza MVP, ale przewidziane w schemacie:

- kolekcje per-miasto/per-gracz,
- custom itemy jako wymagania upgrade'ów,
- paliwa czasowe,
- automatyczna sprzedaż,
- compactor/super compactor,
- minion skins,
- boostery miasta,
- ranking/town perks wpływające na limity i szybkość minionów,
- profile/coop permissions,
- API dla innych pluginów.

---

## 2. Lokalizacja w monorepo

Docelowa struktura:

```text
Plugins/
  HexMinions/
    build.gradle
    README.md
    PLAN.md
    src/main/
      java/hex/minions/
        HexMinionsPlugin.java
        api/
        command/
        config/
        database/
        engine/
        integration/
        integration/deluxemenus/
        listener/
        menu/
        model/
        render/
        service/
        util/
      resources/
        plugin.yml
        config.yml
        minion-types.yml
        resources.yml
        upgrades.yml
        appearance.yml
        menus.yml
        limits.yml
```

`settings.gradle`:

```groovy
include 'plugins:HexMinions'
project(':plugins:HexMinions').projectDir = file('Plugins/HexMinions')
```

`build.gradle`:

```text
plugins {
    id "java"
}

// Ustaw group/version analogicznie do pozostałych pluginów, np. HexTowns.

repositories {
    mavenCentral()
    maven { url = "https://repo.papermc.io/repository/maven-public/" }
}

dependencies {
    compileOnly project(":plugins:HexCore")
    compileOnly project(":plugins:HexTowns")
    compileOnly "io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.jar {
    archiveBaseName = "HexMinions"
}
```

`plugin.yml`:

```yaml
name: HexMinions
version: 1.0.0
main: hex.minions.HexMinionsPlugin
api-version: "1.21"

depend:
  - HexCore
  - HexTowns

commands:
  minion:
    usage: "/minion <give|list|reload|admin>"
    description: Miniony miast Hex SMP
    permission: hexminions.use

permissions:
  hexminions.use:
    default: true
  hexminions.admin:
    default: op
```

---

## 3. Integracja z HexCore i HexTowns

### 3.1 Pobranie API

`HexMinionsPlugin#onEnable`:

```text
var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
if (hexReg == null) { disable; return; }
HexApi hex = hexReg.getProvider();

var townsReg = Bukkit.getServicesManager().getRegistration(TownsApi.class);
if (townsReg == null) { disable; return; }
TownsApi towns = townsReg.getProvider();
```

### 3.2 Kontrakt z miastami

HexMinions używa `TownsApi` do:

| Potrzeba | Metoda |
|---|---|
| sprawdzenie miasta gracza | `townsApi.townIdOf(playerUuid)` |
| sprawdzenie miasta w lokacji | `townsApi.townAt(location)` |
| weryfikacja członkostwa | `townsApi.isMember(playerUuid, townUuid)` |
| dostęp do `Town#internalId()` pod FK DB | `town.internalId()` |
| limit per-miasto z meta | `townsApi.getMetaInt(townUuid, "minions.limit", def)` |
| zapis limitu/override | `townsApi.setMeta(townUuid, "minions.limit", value)` |
| cleanup danych | `townsApi.dataNamespace(plugin, "minions", handler)` |

### 3.3 Namespace danych miasta

Na starcie plugin rejestruje namespace:

```text
townsApi.dataNamespace(this, "minions", (townId, members) -> {
    return minionService.purgeTown(townId);
});
```

Ważne:

- `townId` z handlera powinien odpowiadać `Town#internalId()` (`BIGINT`) używanemu w tabelach HexTowns.
- Jeśli obecna implementacja `TownDataResetHandler` przyjmuje inny typ, agent ma dostosować sygnaturę do faktycznego kodu.
- Tabele HexMinions muszą mieć indeks po `town_id` i cleanup musi być idempotentny.

### 3.4 Eventy i message bus

HexMinions powinien reagować na:

- Bukkit event `TownDestroyedEvent` — jeśli jest dostępny jako compileOnly dependency.
- `HexMessageBus` channel `towns.data.purge` — fallback luźny.
- `HexMessageBus` channel `towns.reset.requested` — tylko jeśli w przyszłości miniony będą miały dane per-player.

HexMinions publikuje kanały:

```text
minions.placed
minions.picked_up
minions.upgraded
minions.storage.claimed
minions.generated
minions.limit.changed
minions.moved
```

Przykład danych `minions.upgraded`:

```text
channel: "minions.upgraded"
data: {
  townId: "...internalId as string...",
  townUuid: "...",
  minionId: "...uuid...",
  type: "cobblestone",
  oldTier: 1,
  newTier: 2,
  byUuid: "..."
}
```

Przykład danych `minions.moved`:

```text
channel: "minions.moved"
data: {
  townId: "...internalId as string...",
  townUuid: "...",
  minionId: "...uuid...",
  type: "cobblestone",
  fromWorld: "world",
  fromX: 10,
  fromY: 64,
  fromZ: 10,
  toWorld: "world",
  toX: 20,
  toY: 64,
  toZ: 20,
  byUuid: "..."
}
```

---

## 4. Model domenowy

### 4.1 `MinionInstance`

```java
public final class MinionInstance {
    UUID id;
    long townInternalId;
    UUID townUuid;
    String typeId;
    int tier;
    String world;
    int blockX, blockY, blockZ;
    float yaw;
    MinionState state; // ACTIVE, DISABLED, CHUNK_UNLOADED, BROKEN, DELETING
    long placedAt;
    long lastActionAt;
    long nextActionAt;
    int storageUsed;
    int storageLimit;
    Map<String, Long> storage; // resourceId -> amount
    List<InstalledUpgrade> upgrades;
    Optional<InstalledFuel> fuel;
    String appearanceId;
}
```

### 4.2 `MinionTypeDefinition`

Definiowany w `minion-types.yml`.

Pola:

- `id`
- `display-name`
- `category`: mining, farming, combat, foraging, fishing, special
- `base-resource-table`
- `tiers`
- `work-area`
- `placement`
- `appearance`
- `menu`
- `upgrade-path`
- `collection-hooks`
- `permissions`

### 4.3 `ResourceDefinition`

Definiowany w `resources.yml`.

Pola:

- `id`
- `display-name`
- `material`
- `custom-model-data` albo przyszły `custom-item-id`
- `collection-id`
- `worth`
- `stack-size`
- `tags`

### 4.4 `UpgradeDefinition`

Definiowany w `upgrades.yml`.

Typy upgrade'ów:

- `SPEED_MULTIPLIER`
- `OUTPUT_MULTIPLIER`
- `STORAGE_BONUS`
- `AUTO_SMELT`
- `COMPACTOR`
- `SUPER_COMPACTOR`
- `AUTO_SELL`
- `FUEL`
- `SKIN`
- `CUSTOM_SCRIPT` — tylko future, nie MVP.

---

## 5. Baza danych

### 5.1 Założenia DB

- DB przez `hex.db().db()` z `HexCore`.
- Wszystkie tabele przez `db.t("...")`, żeby respektować prefix.
- Operacje zapisu asynchroniczne.
- Hot-path nie może wykonywać SELECT per klik/tick.
- `town_id BIGINT UNSIGNED` wskazuje `HexTowns` `towns.id`, czyli `Town#internalId()`.
- UUID miniona trzymamy jako `BINARY(16)`.
- Duże JSON-y nie są trzymane w jednej kolumnie, jeśli dane są często modyfikowane; storage ma osobną tabelę.

### 5.2 Schemat SQL MVP

```sql
CREATE TABLE IF NOT EXISTS {p}minions (
  id              BINARY(16) NOT NULL,
  town_id         BIGINT UNSIGNED NOT NULL,
  town_uuid       BINARY(16) NOT NULL,
  owner_uuid      BINARY(16) NOT NULL,
  type_id         VARCHAR(64) NOT NULL,
  tier            SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  world           VARCHAR(64) NOT NULL,
  x               INT NOT NULL,
  y               SMALLINT NOT NULL,
  z               INT NOT NULL,
  yaw             FLOAT NOT NULL DEFAULT 0,
  state           VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  appearance_id   VARCHAR(64) NULL,
  storage_limit   INT NOT NULL DEFAULT 0,
  storage_used    INT NOT NULL DEFAULT 0,
  placed_at       BIGINT NOT NULL,
  last_action_at  BIGINT NOT NULL,
  next_action_at  BIGINT NOT NULL,
  updated_at      BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_town (town_id),
  KEY idx_town_type (town_id, type_id),
  KEY idx_world_chunk (world, x, z),
  KEY idx_next_action (state, next_action_at),
  UNIQUE KEY uq_location (world, x, y, z)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {p}minion_storage (
  minion_id    BINARY(16) NOT NULL,
  resource_id  VARCHAR(64) NOT NULL,
  amount       BIGINT NOT NULL DEFAULT 0,
  updated_at   BIGINT NOT NULL,
  PRIMARY KEY (minion_id, resource_id),
  KEY idx_resource (resource_id),
  CONSTRAINT fk_minion_storage_minion
    FOREIGN KEY (minion_id) REFERENCES {p}minions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {p}minion_upgrades (
  minion_id      BINARY(16) NOT NULL,
  slot           VARCHAR(32) NOT NULL,
  upgrade_id     VARCHAR(64) NOT NULL,
  level          SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  expires_at     BIGINT NULL,
  data_json      TEXT NULL,
  installed_at   BIGINT NOT NULL,
  PRIMARY KEY (minion_id, slot),
  KEY idx_upgrade (upgrade_id),
  CONSTRAINT fk_minion_upgrades_minion
    FOREIGN KEY (minion_id) REFERENCES {p}minions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {p}town_minion_stats (
  town_id       BIGINT UNSIGNED NOT NULL,
  type_id       VARCHAR(64) NOT NULL,
  placed_count  INT NOT NULL DEFAULT 0,
  total_actions BIGINT NOT NULL DEFAULT 0,
  total_output  BIGINT NOT NULL DEFAULT 0,
  updated_at    BIGINT NOT NULL,
  PRIMARY KEY (town_id, type_id),
  KEY idx_town (town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {p}minion_audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  minion_id     BINARY(16) NULL,
  town_id       BIGINT UNSIGNED NOT NULL,
  actor_uuid    BINARY(16) NULL,
  action        VARCHAR(48) NOT NULL,
  data_json     TEXT NULL,
  created_at    BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_town_created (town_id, created_at),
  KEY idx_minion_created (minion_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

### 5.2.1 Relokacja miniona w DB

Relokacja korzysta z istniejących kolumn `world`, `x`, `y`, `z`, `yaw` i constraintu `UNIQUE KEY uq_location (world, x, y, z)`.

Wymagana metoda repozytorium:

```text
boolean moveMinion(UUID minionId, String world, int x, int y, int z, float yaw, long updatedAt)
```

Implementacja musi wykonywać atomowy update:

```sql
UPDATE {p}minions
SET world=?, x=?, y=?, z=?, yaw=?, updated_at=?
WHERE id=? AND state='ACTIVE'
```

Jeżeli `uq_location` zgłosi konflikt, operacja ma zwrócić błąd `minions.error.location-occupied`, a cache/visuale nie mogą zostać trwale zmienione.

### 5.3 Future DB: kolekcje i custom itemy

Jeżeli system kolekcji nie istnieje jako osobny plugin, można przygotować opcjonalne tabele, ale nie implementować w MVP:

```sql
CREATE TABLE IF NOT EXISTS {p}town_collection_progress (
  town_id        BIGINT UNSIGNED NOT NULL,
  collection_id  VARCHAR(64) NOT NULL,
  amount         BIGINT NOT NULL DEFAULT 0,
  updated_at     BIGINT NOT NULL,
  PRIMARY KEY (town_id, collection_id),
  KEY idx_collection (collection_id)
);

CREATE TABLE IF NOT EXISTS {p}minion_unlocks (
  town_id      BIGINT UNSIGNED NOT NULL,
  unlock_id    VARCHAR(96) NOT NULL,
  source       VARCHAR(64) NOT NULL,
  unlocked_at  BIGINT NOT NULL,
  PRIMARY KEY (town_id, unlock_id)
);
```

Lepsza docelowa architektura: osobny `HexCollections`, a HexMinions tylko publikuje event `minions.generated`
i/lub wywołuje API kolekcji, jeśli jest dostępne.

### 5.4 Migracje

Utworzyć `SchemaMigrator`:

```sql
CREATE TABLE IF NOT EXISTS {p}minions_schema_version (
  version INT NOT NULL,
  applied_at BIGINT NOT NULL,
  PRIMARY KEY (version)
);
```

Agent ma implementować migracje jako idempotentne kroki `V1__initial`, `V2__...`.

### 5.5 Cleanup po usunięciu miasta

Metoda `MinionRepository.deleteByTownId(long townId)`:

1. SELECT minion IDs z `idx_town`.
2. Usuń encje wizualne z pamięci/świata, jeśli są załadowane.
3. `DELETE FROM {p}minions WHERE town_id=?`.
4. Dzięki FK kaskadowo usuwają się `minion_storage` i `minion_upgrades`.
5. Usuń `town_minion_stats` i opcjonalne logi według polityki retention.

Cleanup musi być:

- idempotentny,
- bezpieczny po crashu,
- wykonywany async,
- potwierdzony w namespace handlerze HexTowns.

---

## 6. Indeksy i cache w pamięci

### 6.1 Indeksy runtime

```java
ConcurrentMap<UUID, MinionInstance> minionsById;
ConcurrentMap<Long, Set<UUID>> minionsByTown;
ConcurrentMap<Long, UUID> minionByBlock;       // packed worldId/x/y/z albo hash world+x+y+z
ConcurrentMap<Long, Set<UUID>> minionsByChunk; // world/chunkX/chunkZ
PriorityBlockingQueue<ScheduledMinionAction> actionQueue;
ConcurrentMap<UUID, DirtyMinionState> dirtyWrites;
ConcurrentMap<UUID, OpenMinionContext> openMenusByViewer;
```

### 6.2 Co trzymać w pamięci

Na starcie:

- Załadować lekkie rekordy minionów: `id`, `town_id`, `town_uuid`, `type_id`, `tier`, `location`, `state`, `last_action_at`, `next_action_at`, `storage_used`, `storage_limit`.
- Storage ładować lazy przy:
  - otwarciu menu,
  - generowaniu zasobów,
  - odbiorze zasobów,
  - upgrade.

Opcja dla skali: jeśli minionów będzie bardzo dużo, ładować tylko miniony w aktywnych światach/chunkach, a offline catch-up liczyć z DB przy chunk load.

### 6.3 SLO

Cel wydajnościowy:

- 10 000 miast.
- 50 000 minionów w DB.
- 5 000 aktywnych/załadowanych minionów.
- Brak tickowania wszystkich minionów co tick.
- P99 obsługi PPM miniona < 5 ms na main thread, bez DB sync.
- Flush zmian DB batchowany co 1-5 sekund.

---

## 7. Silnik generowania zasobów

### 7.1 Nie tickujemy każdego miniona

Zakaz:

```text
for every tick:
  for every minion:
    do work
```

Zamiast tego:

- Każdy minion ma `next_action_at`.
- Scheduler trzyma priority queue po `next_action_at`.
- Co `engine.tick-interval-ticks`, np. 20 ticków, pobieramy tylko miniony, których czas akcji minął.
- Jedna iteracja ma budżet `max-actions-per-cycle`.
- Jeśli budżet jest przekroczony, akcje zostają na kolejną iterację.

### 7.2 Algorytm akcji

1. Pobierz miniona z kolejki.
2. Sprawdź czy nadal istnieje i jest `ACTIVE`.
3. Sprawdź czy chunk jest załadowany, jeśli `require-loaded-chunk: true`.
4. Sprawdź warunki typu:
   - miejsce pracy wolne,
   - wymagany blok istnieje,
   - biom/świat dozwolony,
   - storage niepełny.
5. Wylicz output z resource table.
6. Zastosuj upgrade'y i paliwo.
7. Dodaj do storage w pamięci.
8. Zaktualizuj `last_action_at` i `next_action_at`.
9. Oznacz miniona jako dirty do batch flush.
10. Zaktualizuj label/action animation.

### 7.3 Offline catch-up

Po restarcie albo chunk load:

```text
elapsed = now - last_action_at
actionTime = effectiveActionTime(type, tier, fuel, upgrades, townBoosts)
possibleActions = floor(elapsed / actionTime)
actions = min(possibleActions, offline.max-actions-per-minion)
actions = min(actions, freeStorage / avgOutputPerAction)
```

Konfiguracja:

```yaml
engine:
  offline:
    enabled: true
    max-hours: 24
    max-actions-per-minion: 10000
    require-town-active: false
```

MVP: Offline catch-up może być uproszczony do deterministycznego dodawania zasobów bez animacji i bez modyfikacji świata.

### 7.4 Storage full

Gdy storage pełny:

- minion przechodzi w stan runtime `PAUSED_FULL`, ale w DB może zostać `ACTIVE`.
- label pokazuje `<red>Storage full</red>`.
- scheduler recheckuje rzadziej, np. co 60 sekund, albo dopiero po odbiorze storage.

### 7.5 Relokacja miniona do pozycji gracza

Relokacja nie resetuje tieru, storage, paliwa, upgrade'ów ani `last_action_at`. Zmienia wyłącznie pozycję i respawnuje visuale.

Komendy/akcje:

- `/minion move <id>` — przenieś miniona do bloku, na którym stoi gracz albo do wskazanej pozycji zależnie od konfiguracji.
- przycisk GUI `MOVE_HERE` — działa na aktualnie otwartym minionie.
- przyszły adapter DeluxeMenus może wywołać tę samą akcję przez `/minion action move <id>`.

Algorytm:

1. Gracz wybiera miniona (`id` z komendy, GUI albo kontekstu DeluxeMenus).
2. Pobierz `MinionInstance` z cache; jeśli brak, spróbuj lekki async load i wróć na main thread.
3. Zweryfikuj, że gracz należy do miasta miniona: `townsApi.isMember(playerUuid, minion.townUuid())`.
4. Wyznacz docelową lokalizację:
   - domyślnie `player.getLocation().getBlock()` albo blok pod graczem + offset według configu,
   - yaw miniona może zostać ustawiony na yaw gracza.
5. Zweryfikuj, że docelowy chunk należy do tego samego miasta:
   - `townsApi.townAt(targetLocation)` musi zwrócić `townUuid == minion.townUuid`,
   - relokacja do innego miasta lub unclaimed terenu jest zabroniona.
6. Uruchom te same walidacje placementu co przy stawianiu:
   - footprint wolny,
   - dystans od innych minionów,
   - solid ground,
   - blacklist materiałów,
   - świat dozwolony,
   - brak innego miniona w `minionByBlock`.
7. Weź lock per-minion.
8. Despawn visuale starej lokalizacji dopiero po pozytywnej walidacji, ale przed spawnem nowych.
9. Wykonaj DB update async; przy sukcesie wróć na main thread i:
   - zaktualizuj `minionByBlock`, `minionsByChunk`, `MinionInstance.location`,
   - respawn visuale w nowej lokalizacji,
   - odśwież label/menu,
   - opublikuj `minions.moved`.
10. Przy błędzie DB:
    - przywróć cache starej lokalizacji,
    - respawn stare visuale, jeśli zostały usunięte,
    - pokaż błąd graczowi.

Ważne edge-case'y:

- Jeśli gracz stoi poza miastem miniona, relokacja jest odrzucona.
- Jeśli stary chunk jest unloaded, można przenieść miniona bez despawnu starych visuali, ale repair task musi posprzątać orphan entity po chunk load.
- Relokacja nie liczy się jako nowy minion i nie zmienia limitu miasta.
- Relokacja musi być audytowana jako `MOVE` w `minion_audit_log`.

---

## 8. Konfiguracja

### 8.1 `config.yml`

```yaml
minions:
  enabled: true

  placement:
    require-town-member: true
    require-inside-own-town: true
    require-build-permission: true
    min-distance-between-minions: 2
    deny-near-town-border-blocks: 0
    allow-placement-in-unclaimed: false

  relocation:
    enabled: true
    require-same-town: true
    require-town-member: true
    require-build-permission: true
    target: PLAYER_BLOCK          # PLAYER_BLOCK | PLAYER_FEET | TARGET_BLOCK
    use-player-yaw: true
    reuse-placement-rules: true
    audit-log: true

  limits:
    default-town-limit: 5
    meta-key: "minions.limit"
    count-disabled-minions: true
    rank-overrides-enabled: false

  engine:
    tick-interval-ticks: 20
    max-actions-per-cycle: 1000
    max-actions-per-minion-per-cycle: 50
    require-loaded-chunk: false
    dirty-flush-interval-ticks: 100
    dirty-flush-max-rows: 500
    storage-lazy-load: true
    offline:
      enabled: true
      max-hours: 24
      max-actions-per-minion: 10000

  rendering:
    spawn-visuals-on-chunk-load: true
    despawn-visuals-on-chunk-unload: true
    repair-missing-entities-seconds: 30
    label-refresh-ticks: 40

  safety:
    protect-armor-stands: true
    remove-orphan-entities-on-startup: true
    audit-log: true

  database:
    write-behind: true
    batch-storage-updates: true
    batch-stats-updates: true
```

### 8.2 `limits.yml`

```yaml
limits:
  default: 5

  by-town-meta:
    enabled: true
    key: "minions.limit"

  by-permission:
    enabled: true
    values:
      hexminions.limit.5: 5
      hexminions.limit.10: 10
      hexminions.limit.15: 15

  by-town-growth:
    enabled: false
    tiers:
      - min-growth: 0
        limit: 5
      - min-growth: 100
        limit: 7
      - min-growth: 250
        limit: 10

  hard-cap: 30
```

Limit końcowy:

```text
limit = max(default, townMeta, permission, townGrowth, futureTownPerks)
limit = min(limit, hardCap)
```

### 8.3 `resources.yml`

```yaml
resources:
  cobblestone:
    display-name: "<gray>Cobblestone</gray>"
    material: COBBLESTONE
    collection-id: mining_cobblestone
    worth: 1.0
    stack-size: 64
    tags: [mining, block]

  enchanted_cobblestone:
    display-name: "<aqua>Enchanted Cobblestone</aqua>"
    material: COBBLESTONE
    custom-model-data: 10001
    collection-id: mining_cobblestone
    worth: 160.0
    stack-size: 64
    tags: [mining, enchanted]
```

### 8.4 `minion-types.yml`

```yaml
minion-types:
  cobblestone:
    enabled: true
    display-name: "<gray>Cobblestone Minion</gray>"
    category: mining
    item:
      material: PLAYER_HEAD
      display-name: "<gray>Cobblestone Minion <yellow>Tier <tier></yellow></gray>"
      lore:
        - "<dark_gray>Minion miasta</dark_gray>"
        - "<gray>Generuje: <white>Cobblestone</white></gray>"
        - "<gray>Postaw na terenie swojego miasta.</gray>"
      custom-model-data: 0
    placement:
      footprint-radius-blocks: 1
      require-solid-ground: true
      blocked-materials: [WATER, LAVA]
    work-area:
      radius: 2
      height: 2
      mode: VIRTUAL
    appearance: cobblestone_default
    menu: default_minion
    resource-table:
      - resource: cobblestone
        amount-min: 1
        amount-max: 1
        chance: 1.0
    tiers:
      1:
        action-time-seconds: 14
        storage: 64
        upgrade-cost:
          resources:
            cobblestone: 80
      2:
        action-time-seconds: 13
        storage: 128
        upgrade-cost:
          resources:
            cobblestone: 160
      3:
        action-time-seconds: 12
        storage: 192
        upgrade-cost:
          resources:
            cobblestone: 320
    max-tier: 11
```

### 8.5 Upgrade requirements DSL

Upgrade warunki mają być generyczne i konfigurowalne:

```yaml
upgrade-cost:
  resources:
    cobblestone: 160
    enchanted_cobblestone: 1
  town-growth-points: 5
  money: 1000
  permissions:
    - "hexminions.upgrade.cobblestone.2"
  collections:
    mining_cobblestone: 500
  custom-items:
    - id: "hex:gear_core"
      amount: 1
```

MVP implementuje:

- `resources`,
- opcjonalnie `town-growth-points` tylko jako check, bez konsumowania, chyba że user zdecyduje inaczej,
- `permissions`.

Future:

- `money`,
- `collections`,
- `custom-items` przez API innych pluginów.

### 8.6 `upgrades.yml`

```yaml
upgrades:
  enchanted_lava_bucket:
    type: FUEL
    display-name: "<gold>Enchanted Lava Bucket</gold>"
    item:
      material: LAVA_BUCKET
      custom-model-data: 20001
    effects:
      speed-multiplier: 1.25
    duration-seconds: -1

  diamond_spreading:
    type: OUTPUT_BONUS
    display-name: "<aqua>Diamond Spreading</aqua>"
    item:
      material: DIAMOND
      custom-model-data: 20002
    effects:
      extra-resource:
        resource: diamond
        chance: 0.1
        amount: 1

  compactor:
    type: COMPACTOR
    display-name: "<green>Compactor</green>"
    effects:
      recipes:
        cobblestone: enchanted_cobblestone
      input-amount: 160
      output-amount: 1
```

### 8.7 `appearance.yml`

```yaml
appearances:
  cobblestone_default:
    base:
      type: ARMOR_STAND
      marker: false
      small: true
      invisible: false
      invulnerable: true
      no-gravity: true
      arms: true
      equipment-locked: true
      pose:
        head: [0, 0, 0]
        body: [0, 0, 0]
        left-arm: [0, 0, 0]
        right-arm: [0, 0, 0]
      equipment:
        helmet:
          material: PLAYER_HEAD
          skull-texture: ""
        chestplate:
          material: LEATHER_CHESTPLATE
          color: "#777777"
        leggings:
          material: LEATHER_LEGGINGS
          color: "#555555"
        boots:
          material: LEATHER_BOOTS
          color: "#333333"
        main-hand:
          material: WOODEN_PICKAXE

    attachments:
      - id: block_backpack
        type: BLOCK_DISPLAY
        material: COBBLESTONE
        offset: [0.0, 0.65, -0.25]
        scale: [0.45, 0.45, 0.45]

    label:
      type: TEXT_DISPLAY
      offset: [0.0, 1.65, 0.0]
      billboard: CENTER
      text: "<yellow><name></yellow> <gray>Tier <tier></gray>\n<storage_bar>"
      shadowed: true
      see-through: false
```

Rekomendacja dla Paper 1.21:

- `ArmorStand` jako ciało miniona.
- `TextDisplay` jako label nad minionem.
- `BlockDisplay`/`ItemDisplay` jako dekoracje.
- Wszystkie entity mają PDC:
  - `hexminions:minion_id`,
  - `hexminions:part_id`,
  - `hexminions:protected`.

---

## 9. UI po kliknięciu PPM

### 9.1 Decyzja: natywne Paper Inventory GUI zamiast DeluxeMenus

Rekomenduję **własne natywne GUI oparte o Paper/Bukkit Inventory API** i konfigurowalne layouty YAML.

Uzasadnienie:

| Kryterium | Paper Inventory GUI | DeluxeMenus |
|---|---|---|
| Dynamiczny storage miniona | bardzo łatwy | trudny/placeholder-heavy |
| Walidacja upgrade'u w kodzie | pełna kontrola | wymaga komend/hooków |
| Brak dodatkowej zależności | tak | nie |
| Integracja z custom itemami/PDC | pełna | ograniczona |
| Menu per-instance | naturalne | bardziej statyczne |
| Bezpieczeństwo przed dupe | pełna kontrola eventów | większe ryzyko błędnej konfiguracji |

DeluxeMenus można dodać później jako **adapter widoku**, ale nie jako core logiki.

### 9.2 `menus.yml`

```yaml
menus:
  default_minion:
    title: "<dark_gray>Minion: <name> <gray>Tier <tier></gray>"
    rows: 6
    refresh-ticks: 20
    filler:
      material: BLACK_STAINED_GLASS_PANE
      name: " "

    slots:
      info:
        slot: 4
        material: PLAYER_HEAD
        name: "<yellow><name></yellow>"
        lore:
          - "<gray>Tier: <white><tier></white>/<max_tier></gray>"
          - "<gray>Akcja co: <white><action_time>s</white></gray>"
          - "<gray>Storage: <storage_used>/<storage_limit></gray>"
          - "<gray>Miasto: <white><town></white></gray>"

      storage-start:
        slots: [19,20,21,22,23,24,25,28,29,30,31,32,33,34]
        type: STORAGE_GRID

      collect:
        slot: 48
        material: CHEST
        name: "<green>Odbierz surowce</green>"
        lore:
          - "<gray>Kliknij, aby przenieść storage do ekwipunku.</gray>"
        action: COLLECT_STORAGE

      upgrade:
        slot: 50
        material: ANVIL
        name: "<gold>Ulepsz do Tier <next_tier></gold>"
        lore:
          - "<gray>Wymagania:</gray>"
          - "<requirements>"
        action: UPGRADE_TIER

      pickup:
        slot: 53
        material: BARRIER
        name: "<red>Podnieś miniona</red>"
        lore:
          - "<gray>Minion wróci jako item.</gray>"
          - "<red>Wymaga pustego miejsca w ekwipunku.</red>"
        action: PICKUP_MINION

      move-here:
        slot: 45
        material: ENDER_PEARL
        name: "<aqua>Przenieś tutaj</aqua>"
        lore:
          - "<gray>Przenosi tego miniona do miejsca,</gray>"
          - "<gray>w którym teraz stoisz.</gray>"
          - "<dark_gray>Musi to być teren tego samego miasta.</dark_gray>"
        action: MOVE_HERE

      fuel:
        slot: 10
        type: FUEL_SLOT
        empty-material: BUCKET
        name: "<yellow>Paliwo</yellow>"

      upgrade-1:
        slot: 16
        type: UPGRADE_SLOT
        accepted-tags: [minion_upgrade]
```

### 9.3 Menu event rules

`InventoryClickEvent`:

- anuluj wszystkie kliknięcia w GUI domyślnie,
- przepuszczaj tylko sloty typu `FUEL_SLOT` i `UPGRADE_SLOT` przez kontrolowaną ścieżkę,
- nigdy nie ufaj itemowi po stronie klienta,
- waliduj PDC itemu,
- po każdej zmianie odśwież widok.

`InventoryCloseEvent`:

- zapisz pending slot changes,
- jeśli gracz zamknął menu podczas operacji async, zabezpiecz transakcję przez lock miniona.

### 9.4 Przyszła integracja z DeluxeMenus

Core logiki pozostaje w HexMinions, ale od początku trzeba przygotować **warstwę danych i akcji**, którą DeluxeMenus będzie mógł łatwo wykorzystać.

Założenie: DeluxeMenus najlepiej traktować jako zewnętrzny renderer menu. Nie może być źródłem prawdy o minionach ani samodzielnie wykonywać upgrade'ów/pickupów bez walidacji w HexMinions.

#### 9.4.1 Kontrakt danych dla menu

Utworzyć serwis:

```java
public interface MinionMenuDataService {
    TownMinionMenuData townData(Player viewer);
    Optional<MinionMenuData> minionData(Player viewer, UUID minionId);
    Optional<MinionMenuData> minionByIndex(Player viewer, int index);
}
```

`TownMinionMenuData`:

```java
public record TownMinionMenuData(
    UUID townUuid,
    String townName,
    int minionCount,
    int minionLimit,
    List<MinionMenuData> minions
) {}
```

`MinionMenuData` ma zawierać wyłącznie gotowe, bezpieczne do wyświetlenia pola:

- `id`,
- `shortId`,
- `typeId`,
- `displayName`,
- `tier`,
- `maxTier`,
- `world`, `x`, `y`, `z`,
- `storageUsed`, `storageLimit`, `storagePercent`,
- `actionTimeSeconds`,
- `state`,
- `canUpgrade`,
- `nextUpgradeRequirementsText`,
- `menuSlotHint`.

Lista minionów musi mieć stabilną kolejność, np.:

```text
ORDER BY placed_at ASC, id ASC
```

Dzięki temu DeluxeMenus może wyświetlać pozycje `1`, `2`, `3` itd. bez przeskakiwania elementów między odświeżeniami.

#### 9.4.2 PlaceholderAPI / DeluxeMenus placeholders

Jeśli na serwerze istnieje PlaceholderAPI, dodać opcjonalny soft-hook `HexMinionsPlaceholderExpansion`.

Proponowane placeholdery globalne dla miasta gracza:

```text
%hexminions_town_count%
%hexminions_town_limit%
%hexminions_town_free_slots%
%hexminions_town_has_minion_1%
%hexminions_town_has_minion_2%
%hexminions_town_has_minion_<index>%
```

Placeholdery indeksowane — `<index>` zaczyna się od 1 i odnosi się do stabilnie posortowanej listy minionów miasta:

```text
%hexminions_minion_1_id%
%hexminions_minion_1_short_id%
%hexminions_minion_1_name%
%hexminions_minion_1_type%
%hexminions_minion_1_tier%
%hexminions_minion_1_max_tier%
%hexminions_minion_1_state%
%hexminions_minion_1_location%
%hexminions_minion_1_storage_used%
%hexminions_minion_1_storage_limit%
%hexminions_minion_1_storage_percent%
%hexminions_minion_1_can_upgrade%
%hexminions_minion_1_upgrade_requirements%
```

Placeholdery kontekstowe dla aktualnie wybranego miniona gracza:

```text
%hexminions_selected_id%
%hexminions_selected_name%
%hexminions_selected_tier%
%hexminions_selected_storage_percent%
```

Kontekst wybranego miniona można ustawiać komendą:

```text
/minion select <id>
/minion select-index <index>
```

To pozwala DeluxeMenus otworzyć drugie menu szczegółów po kliknięciu slotu z minionem.

#### 9.4.3 Komendy akcji dla DeluxeMenus

DeluxeMenus powinien wykonywać tylko komendy HexMinions, a HexMinions robi pełną walidację.

```text
/minion menu town
/minion menu details <id>
/minion select <id>
/minion select-index <index>
/minion action collect <id>
/minion action upgrade <id>
/minion action pickup <id>
/minion action move <id>
/minion action teleport <id>       # optional/admin albo future
```

Wszystkie akcje muszą:

- sprawdzić członkostwo w mieście,
- sprawdzić stan miniona,
- użyć tych samych locków i serwisów co natywne GUI,
- zwrócić komunikat przez `hex.ui()`.

#### 9.4.4 Sloty dynamiczne w DeluxeMenus

Dodać do planu przykładowy kontrakt layoutu DeluxeMenus:

```yaml
items:
  minion_1:
    slot: 10
    display_name: "%hexminions_minion_1_name%"
    lore:
      - "Tier: %hexminions_minion_1_tier%/%hexminions_minion_1_max_tier%"
      - "Storage: %hexminions_minion_1_storage_percent%%"
      - "Lokacja: %hexminions_minion_1_location%"
    view_requirement:
      requirements:
        has_minion:
          type: string equals
          input: "%hexminions_town_has_minion_1%"
          output: "true"
    left_click_commands:
      - "[player] minion select-index 1"
      - "[openguimenu] minion_details"

  minion_2:
    slot: 11
    display_name: "%hexminions_minion_2_name%"
    view_requirement:
      requirements:
        has_minion:
          type: string equals
          input: "%hexminions_town_has_minion_2%"
          output: "true"
    left_click_commands:
      - "[player] minion select-index 2"
      - "[openguimenu] minion_details"
```

Jeśli limit minionów może być większy niż liczba slotów w jednym menu, przewidzieć paginację:

```text
/minion menu town <page>
%hexminions_page_current%
%hexminions_page_total%
%hexminions_page_has_next%
%hexminions_page_has_prev%
%hexminions_page_1_name% ... %hexminions_page_28_name%
```

#### 9.4.5 Plik konfiguracyjny adaptera DeluxeMenus

Dodać opcjonalny plik `deluxemenus.yml` albo sekcję w `menus.yml`:

```yaml
deluxemenus:
  enabled: false
  require-placeholderapi: true
  town-menu-name: "minions_town"
  details-menu-name: "minion_details"
  indexed-placeholders:
    max-index: 30
  page-placeholders:
    enabled: true
    page-size: 28
  selected-context:
    ttl-seconds: 120
```

MVP nie musi wymagać DeluxeMenus, ale klasy powinny być zaprojektowane tak, żeby adapter można było dodać bez zmian w silniku minionów.

---

## 10. Wygląd i ochrona entity

### 10.1 Spawn visuali

`MinionRenderer`:

1. Na podstawie `appearanceId` buduje entity.
2. Każde entity dostaje PDC `minion_id` i `part_id`.
3. Armor stand:
   - `setInvulnerable(true)`,
   - `setGravity(false)`,
   - equipment locks dla każdego slotu,
   - `setCanPickupItems(false)`,
   - `setPersistent(true)` albo kontrolowana respawn polityka.
4. Label jako `TextDisplay`, nie custom name armor standa.

### 10.2 Ochrona entity

Listenery:

- `EntityDamageByEntityEvent` — anuluj dla części miniona.
- `PlayerArmorStandManipulateEvent` — anuluj zawsze dla miniona.
- `EntityCombustEvent` — anuluj.
- `EntityExplodeEvent` / `ExplosionPrimeEvent` — zabezpiecz części.
- `ChunkLoadEvent` — respawn brakujących visuali.
- `ChunkUnloadEvent` — opcjonalny despawn visuali z cache.

### 10.3 Wykrywanie kliknięcia PPM

Listenery:

- `PlayerInteractAtEntityEvent` dla `ArmorStand`/`Interaction`/`Display` jeśli wspierane.
- `PlayerInteractEntityEvent` fallback.

Po kliknięciu:

1. Odczytaj `minion_id` z PDC entity.
2. Znajdź `MinionInstance` w cache.
3. Zweryfikuj `townsApi.isMember(playerUuid, townUuid)`.
4. Otwórz menu.

---

## 11. Komendy i permisje

### 11.1 Komendy gracza

| Komenda | Permisja | Opis |
|---|---|---|
| `/minion list` | `hexminions.use` | Lista minionów miasta gracza, lokalizacje, tier, storage. |
| `/minion pickup <id>` | `hexminions.use` | Podnosi miniona, jeśli gracz jest członkiem miasta. |
| `/minion move <id>` | `hexminions.use` | Przenosi miniona do lokalizacji gracza w obrębie tego samego miasta. |
| `/minion select <id>` / `/minion select-index <index>` | `hexminions.use` | Ustawia kontekst wybranego miniona dla menu/DeluxeMenus. |
| `/minion help` | `hexminions.use` | Pomoc. |

### 11.2 Komendy admina

| Komenda | Permisja | Opis |
|---|---|---|
| `/minion give <player> <type> [tier] [amount]` | `hexminions.admin` | Daje item miniona. |
| `/minion reload` | `hexminions.admin` | Przeładowuje konfiguracje i layouty. |
| `/minion admin setlimit <town/player> <limit>` | `hexminions.admin` | Ustawia `minions.limit` w meta miasta. |
| `/minion admin debug <id>` | `hexminions.admin` | Diagnostyka miniona. |
| `/minion admin repair` | `hexminions.admin` | Respawn/cleanup visuali. |
| `/minion admin purge-town <townId>` | `hexminions.admin` | Awaryjny cleanup. |
| `/minion admin metrics` | `hexminions.admin` | Kolejki, cache, DB flush, aktywne miniony. |

---

## 12. Publiczne API HexMinions

Utworzyć `hex.minions.api.MinionsApi` i zarejestrować w `ServicesManager`.

```java
public interface MinionsApi {
    Optional<MinionView> findMinion(UUID minionId);
    List<MinionView> minionsOfTown(UUID townUuid);
    int countMinions(UUID townUuid);
    int maxMinions(UUID townUuid);
    boolean canPlace(Player player, Location location, String typeId);
    CompletableFuture<PlaceResult> place(Player player, Location location, ItemStack minionItem);
    CompletableFuture<PickupResult> pickup(Player player, UUID minionId);
    CompletableFuture<MoveResult> move(Player player, UUID minionId, Location targetLocation);
    CompletableFuture<UpgradeResult> upgrade(Player player, UUID minionId);
    TownMinionMenuData menuData(Player viewer);
    Optional<MinionMenuData> menuData(Player viewer, UUID minionId);
    Optional<MinionMenuData> menuDataByIndex(Player viewer, int index);
    void registerListener(MinionsListener listener);
}
```

`MinionView` musi być immutable i nie ujawniać mutowalnej mapy storage.

Metody `menuData*` są celowo read-only i mają być tanie: korzystają z cache i stabilnie posortowanych indeksów, żeby mogły być używane przez natywne GUI, PlaceholderAPI i przyszły adapter DeluxeMenus.

---

## 13. Integracja z przyszłymi kolekcjami i custom itemami

### 13.1 Kolekcje

Przy wygenerowaniu zasobów HexMinions powinien:

1. Dodać storage miniona.
2. Opublikować `minions.generated` z listą zasobów.
3. Jeśli istnieje `HexCollectionsApi`, wywołać `addProgress(townUuid/playerUuid, collectionId, amount, source="minion")`.

Nie implementować twardej zależności na kolekcje w MVP.

### 13.2 Custom itemy

Wymagania upgrade'ów powinny używać abstrakcji:

```java
interface ItemMatcher {
    boolean matches(ItemStack stack, ItemRequirement req);
}
```

MVP matchery:

- Bukkit `Material`,
- `custom-model-data`,
- PDC `hex:item_id` jeśli istnieje.

Future:

- integracja z custom items pluginem,
- NBT/API ItemsAdder/Oraxen, jeśli serwer kiedyś ich użyje.

---

## 14. Optymalizacje i bezpieczeństwo wydajności

### 14.1 Main thread budget

Na main thread wolno:

- spawn/despawn entity,
- otwieranie GUI,
- odświeżanie itemów w inventory,
- odczyt cache,
- podstawowe walidacje.

Na main thread nie wolno:

- wykonywać zapytań DB,
- serializować dużych JSON-ów,
- skanować wszystkich minionów,
- przeliczać offline catch-up dla tysięcy minionów naraz.

### 14.2 Write-behind DB

Zamiast zapisywać DB po każdej akcji:

- `DirtyMinionState` agreguje zmiany:
  - `storageDelta`,
  - `lastActionAt`,
  - `nextActionAt`,
  - `storageUsed`,
  - `statsDelta`.
- Co `dirty-flush-interval-ticks` task async robi batch:
  - batch update `minions`,
  - batch upsert `minion_storage`,
  - batch upsert `town_minion_stats`.

Przykład SQL storage:

```sql
INSERT INTO {p}minion_storage (minion_id, resource_id, amount, updated_at)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
  amount = amount + VALUES(amount),
  updated_at = VALUES(updated_at);
```

### 14.3 Locking

- Lock per minion: `StripedLock<UUID>` albo `ConcurrentHashMap<UUID, ReentrantLock>`.
- Operacje upgrade/pickup/collect muszą brać lock miniona.
- Operacje limitu miasta muszą brać lock miasta lub używać transakcji z warunkiem.
- DB unique `uq_location` zabezpiecza przed duplikatem w tej samej lokacji.

### 14.4 Chunk awareness

- `minionsByChunk` pozwala obsługiwać tylko miniony w chunku na `ChunkLoadEvent`/`ChunkUnloadEvent`.
- Renderer nie przeszukuje świata, tylko operuje po PDC i cache.
- Startup cleanup orphan entity:
  - skan tylko załadowanych chunków,
  - usuń entity z PDC `hexminions:protected`, które nie mają rekordu w cache.

### 14.5 Backpressure

Gdy queue jest przeciążona:

- ogranicz `max-actions-per-cycle`,
- zwiększ `next_action_at` zaległych minionów minimalnie, żeby uniknąć spiral lag,
- loguj warning z metryką, nie spamuj konsoli,
- `/minion admin metrics` pokazuje backlog.

### 14.6 Memory

- Definicje configów immutable po reloadzie.
- Storage lazy load i LRU cache, np. max 5000 minionów storage w pamięci.
- `MinionView` bez kopii dużych map, chyba że wymagane przez API.
- Unikać `Location` jako klucza mapy; używać packed long/string key.

---

## 15. Edge cases

1. Gracz próbuje postawić miniona poza swoim miastem → blokada.
2. Miasto osiągnęło limit → blokada i komunikat z aktualnym limitem.
3. Gracz z COOP stawia miniona → dozwolone, jeśli jest członkiem miasta; opcjonalnie permisje miasta future.
4. Miasto zostaje zniszczone, gdy menu miniona jest otwarte → zamknij menu i anuluj operacje.
5. Minion jest w chunku unloaded → nie spawnuj visuali; generator zależnie od configu może działać offline.
6. Brak definicji typu po reloadzie → minion `DISABLED_MISSING_TYPE`, nie usuwać danych.
7. Brak definicji resource → nie generować tego resource, log warning raz per typ.
8. Storage pełny → pauza generowania, label update.
9. Inventory gracza pełne przy odbiorze → dodaj ile się da, resztę zostaw w storage.
10. Pickup przy pełnym inventory → odmów albo drop item tylko jeśli config `drop-on-full-inventory: true`.
11. Relokacja miniona poza miasto albo do innego miasta → odmów, nawet jeśli gracz ma permisję admina, chyba że używa osobnej komendy admin override.
12. Relokacja na zajęty blok / za blisko innego miniona → odmów i nie zmieniaj DB/cache.
13. Relokacja podczas otwartego menu przez innego gracza → lock miniona, odśwież wszystkie otwarte widoki po sukcesie.
14. Crash podczas pickup → kolejność transakcji musi zapobiegać dupe:
    - najpierw lock,
    - oznacz `DELETING`,
    - usuń visuale,
    - zapisz DB,
    - daj item lub zapisz pending claim.
15. Reload configu zmienia action time → przelicz `next_action_at` bez resetu progresu.
16. Zmiana `appearance.yml` → `/minion admin repair` respawnuje visuale.
17. Inny plugin zabije armor stand → repair task odtworzy visual.
18. Admin usunie świat → miniony w tym świecie jako `DISABLED_WORLD_MISSING`.
19. DeluxeMenus pyta o indeks większy niż liczba minionów → placeholder zwraca pusty string albo `false` dla `has_minion`.

---

## 16. UI teksty przez HexCore

Wszystkie komunikaty przez `hex.ui()` namespace `minions`.

Przykładowe klucze:

```text
minions.error.no-town
minions.error.not-member
minions.error.not-in-own-town
minions.error.limit-reached
minions.error.unknown-type
minions.error.storage-full
minions.error.inventory-full
minions.place.success
minions.pickup.success
minions.collect.success
minions.upgrade.success
minions.upgrade.missing-requirements
minions.move.success
minions.move.error.not-same-town
minions.move.error.location-invalid
minions.move.error.location-occupied
minions.reload.success
minions.admin.limit-set
minions.admin.metrics
```

Domyślne teksty można rejestrować w `HexMinionsPlugin#registerUiDefaults()` analogicznie do `HexTownsPlugin`.

---

## 17. Implementacja — kolejność dla agenta

### Iteracja 1: szkielet

1. Utwórz moduł `Plugins/HexMinions`.
2. Dopisz moduł do `settings.gradle`.
3. Dodaj `build.gradle`, `plugin.yml`, domyślne YAML-e.
4. `HexMinionsPlugin#onEnable`:
   - `saveDefaultConfig`,
   - pobierz `HexApi`,
   - pobierz `TownsApi`,
   - załaduj konfiguracje,
   - uruchom migracje DB,
   - załaduj indeksy,
   - zarejestruj namespace `minions`,
   - zarejestruj komendy/listenery/API.

### Iteracja 2: DB i modele

1. `MinionRepository#ensureTables()`.
2. `MinionRepository#loadInitialState()`.
3. Modele definicji configów.
4. Parser YAML z walidacją i raportowaniem błędów.
5. Test jednostkowy parserów configu.

### Iteracja 3: placement i item

1. `MinionItemFactory` z PDC `typeId`, `tier`.
2. Listener `BlockPlaceEvent` albo `PlayerInteractEvent` dla itemu miniona.
3. Walidacja miasta i limitu.
4. Insert DB + cache.
5. Spawn visuali.

### Iteracja 3.5: relokacja miniona

1. `MinionPlacementValidator` jako wspólna walidacja dla placementu i relokacji.
2. `MinionMoveService#moveToPlayerLocation(Player, UUID)`.
3. Komenda `/minion move <id>` i akcja GUI `MOVE_HERE`.
4. Atomowy update DB pozycji i aktualizacja indeksów `minionByBlock` / `minionsByChunk`.
5. Respawn visuali i publikacja `minions.moved`.

### Iteracja 4: renderer i ochrona entity

1. `MinionRenderer` dla ArmorStand/TextDisplay/BlockDisplay.
2. PDC na entity.
3. Listenery ochrony.
4. Repair task.

### Iteracja 5: menu

1. `MenuLayoutLoader` z `menus.yml`.
2. `MinionMenu` i `MinionMenuHolder`.
3. Akcje `COLLECT_STORAGE`, `UPGRADE_TIER`, `PICKUP_MINION`.
4. Akcja `MOVE_HERE` przenosząca miniona do pozycji gracza w obrębie tego samego miasta.
5. Sloty paliwa/upgrade'ów jako MVP optional, ale schema gotowa.

### Iteracja 5.5: dane pod DeluxeMenus

1. `MinionMenuDataService` z read-only DTO dla miasta i minionów.
2. Stabilne sortowanie minionów miasta i obsługa indeksów 1..N.
3. Komendy `/minion select`, `/minion select-index`, `/minion action ...`.
4. Opcjonalny soft-hook PlaceholderAPI, jeśli dependency jest dostępne.
5. Dokumentacja przykładowego layoutu DeluxeMenus w `README.md`.

### Iteracja 6: engine

1. Priority queue `next_action_at`.
2. Generator outputu.
3. Storage cache.
4. Dirty write-behind.
5. Offline catch-up.
6. Metrics.

### Iteracja 7: cleanup i eventy

1. `TownDestroyedEvent` listener.
2. `TownDataNamespace` purge handler.
3. `HexMessageBus` publish events.
4. `/minion admin purge-town`.

### Iteracja 8: testy i smoke test

1. Test parserów configu.
2. Test kalkulacji action time i offline catch-up.
3. Test limitu miasta.
4. Test upgrade requirements.
5. Smoke test na serwerze Paper.

---

## 18. Test plan

### 18.1 Testy jednostkowe

- `MinionTypeConfigTest`:
  - poprawny typ,
  - brak tieru 1,
  - brak resource,
  - invalid material.
- `ResourceTableTest`:
  - deterministic output przy seed,
  - chance 0/1,
  - amount range.
- `OfflineCatchupTest`:
  - zero elapsed,
  - max-hours cap,
  - storage cap,
  - speed multiplier.
- `LimitResolverTest`:
  - default,
  - town meta,
  - permission,
  - hard cap.

### 18.2 Smoke test na serwerze

1. Uruchom `HexCore`, `HexTowns`, `HexMinions`.
2. Stwórz miasto `/town create`.
3. Daj miniona `/minion give <nick> cobblestone 1`.
4. Postaw miniona w mieście → sukces.
5. Postaw poza miastem → blokada.
6. Otwórz PPM menu → widoczny storage i upgrade.
7. Poczekaj na kilka akcji → storage rośnie.
8. Odbierz storage → itemy trafiają do ekwipunku.
9. Ulepsz miniona → tier rośnie, action time/storage się zmienia.
10. Użyj `/minion move <id>` stojąc w innym miejscu tego samego miasta → minion przenosi visuale i zachowuje storage/tier.
11. Użyj `/minion move <id>` stojąc poza miastem → operacja zablokowana.
12. Osiągnij limit miasta → kolejny placement zablokowany.
13. `/town destroy` → miniony i storage miasta usunięte.
14. Restart serwera → brak orphan armor standów, miniony wracają poprawnie.

### 18.3 Test wydajnościowy

Przygotować komendę admin/dev:

```text
/minion admin benchmark spawnfake <towns> <minionsPerTown>
/minion admin benchmark engine <seconds>
```

Cele:

- 10k rekordów minionów ładuje indeksy bez timeoutu.
- Scheduler nie przekracza budżetu `max-actions-per-cycle`.
- Flush DB robi batche, nie pojedyncze update'y.
- `/minion admin metrics` pokazuje backlog i dirty queue.

---

## 19. Checklist akceptacyjny

Agent kończący implementację musi odhaczyć:

1. [ ] Moduł `Plugins/HexMinions` kompiluje się z Gradle.
2. [ ] Plugin startuje tylko gdy dostępne są `HexCore` i `HexTowns`.
3. [ ] Tabele DB tworzą się idempotentnie.
4. [ ] `TownDataNamespace("minions")` jest zarejestrowany.
5. [ ] Miniona można dać itemem i postawić w mieście.
6. [ ] Miniona nie można postawić poza miastem ani ponad limitem.
7. [ ] Visual miniona ma protected PDC i nie da się zdejmować zbroi/itemów.
8. [ ] Label nad minionem pokazuje nazwę i tier.
9. [ ] PPM otwiera menu.
10. [ ] Storage generuje surowce bez per-tick skanowania wszystkich minionów.
11. [ ] Odbiór storage nie duplikuje itemów przy pełnym inventory.
12. [ ] Upgrade działa przez konfigurowalne requirements.
13. [ ] Pickup usuwa DB/cache/visuale i zwraca item.
14. [ ] `/minion move <id>` przenosi miniona do pozycji gracza tylko w obrębie tego samego miasta.
15. [ ] Relokacja zachowuje tier, storage, paliwo, upgrade'y i harmonogram generowania.
16. [ ] `MinionMenuDataService` zwraca stabilnie posortowane dane pod natywne GUI i przyszłe DeluxeMenus.
17. [ ] Placeholdery/komendy akcji pod DeluxeMenus są opisane lub zaimplementowane jako soft-hook.
18. [ ] `/town destroy` czyści dane minionów.
19. [ ] Reload konfiguracji nie usuwa danych graczy.
20. [ ] Brak synchronicznych zapytań DB w listenerach kliknięć i tick engine.
21. [ ] Testy jednostkowe parserów i kalkulacji przechodzą.
22. [ ] Smoke test wykonany na Paper 1.21.x.

---

## 20. Decyzje do potwierdzenia przed pełną implementacją

1. Czy COOP może stawiać/podnosić/ulepszać miniony, czy tylko owner miasta?
2. Czy miniony mają działać offline, gdy chunk jest wyładowany?
3. Czy upgrade ma konsumować `town growth points`, czy tylko wymagać progu?
4. Czy limit minionów ma zależeć od rangi/permisji właściciela, miasta, czy obu?
5. Czy storage ma generować realne itemy Bukkit, czy zasoby logiczne zamieniane na item przy odbiorze?
6. Czy minion może modyfikować świat fizycznie, czy MVP zawsze używa trybu `VIRTUAL`?
7. Czy kolekcje będą osobnym pluginem `HexCollections`, czy częścią `HexMinions`?
8. Czy relokację miniona może wykonywać każdy członek COOP, czy tylko owner / rola z przyszłych uprawnień miasta?
9. Czy adapter DeluxeMenus ma być tylko PlaceholderAPI + komendy, czy także gotowy generator przykładowych plików menu?

Rekomendacje domyślne dla MVP:

- COOP może obsługiwać miniony, ale pickup/upgrade można ograniczyć do ownera configiem.
- Offline generation włączone z capem 24h.
- Growth points jako wymaganie, nie koszt.
- Storage logiczny, itemy tworzone dopiero przy odbiorze.
- Miniony nie modyfikują świata fizycznie w MVP (`work-area.mode: VIRTUAL`).
- Kolekcje jako osobny przyszły plugin.
- Relokacja dostępna dla ownera i COOP, z możliwością ograniczenia configiem.
- DeluxeMenus jako future adapter: PlaceholderAPI + komendy akcji, bez przenoszenia logiki poza HexMinions.

