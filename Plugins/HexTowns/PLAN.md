# HexTowns — plan techniczny pluginu miast SMP

> Plan wykonania dla agenta AI tworzącego plugin **HexTowns** w monorepo `HexPlugins`.
> Plugin jest fundamentem ekosystemu SMP (miasta, claim chunków, COOP, SafeZone, hub danych
> dla innych pluginów: minionów, kolekcji, skilli, inwazji, ekonomii, EQ).
>
> **Założenie nadrzędne**: HexTowns nie zna innych pluginów SMP. Inne pluginy:
> - czytają dane miasta przez **HexTowns API** (ServicesManager) gdy potrzebują twardych danych,
> - reagują na **eventy Bukkit** + **HexMessageBus** gdy chcą się luźno spiąć (np. reset stat po `town.destroy`).
>
> Bazą danych, UI/i18n, regionami i sygnałami między-pluginowymi zarządza **HexCore**.

> **Cele skali (SLO):** serwer ma utrzymać do **1000 graczy online** i **10 000 miast** w bazie
> bez spadków TPS. Każda operacja gracza (`create`, `claim`, ruch, build) musi być **O(1)** lub
> ograniczona do małego, lokalnego okna chunków — nigdy skanowania całej tabeli/zbioru miast.

---

## 1. Zakres MVP

W zakresie pierwszej iteracji:

1. `/town create` — założenie miasta o rozmiarze startowym 3×3 chunki (konfigurowalne).
2. Walidacja odległości — min. **10 chunków** od dowolnego innego miasta (konfig).
3. Ochrona chunków (block place/break, interact, container, PvP) — SafeZone.
4. `/town claim` — powiększanie miasta o przylegający chunk, z zachowaniem **1 chunka buforu** między miastami.
5. Limit max chunków (konfig, MVP = 49) i licznik **growth points** (punkty rośnięcia).
6. COOP — max **3 graczy** w mieście. Komendy: `/town coop`, `/town accept`, `/town endcoop`.
7. `/town destroy` — dwustopniowe potwierdzenie + wyemitowanie eventu/wiadomości o resecie.
8. `/town check` — wizualizacja terenu miasta (fake blocks) per gracz, toggle.
9. Hub danych — tabela miast w bazie + API + meta (np. `minions.limit`, dowolne klucze pluginów).
10. Integracja UI/teksty — wyłącznie przez `api.ui()` (MiniMessage, namespace `towns`).

Poza MVP (do wdrożenia w iteracjach, ale plan musi je przewidzieć w API/schemacie):

- Inwazje, „serce bazy”, custom TNT, growth points zdobywane z innych systemów,
- reset statystyk gracza (HexTowns tylko **publikuje** event — reset wykonują inne pluginy),
- ranga gracza (VIP/SVIP/Elita) wpływająca na limity — odczyt z HexCore.

---

## 2. Lokalizacja w monorepo

```
Plugins/
  HexTowns/
    build.gradle
    README.md
    src/main/
      java/hex/towns/
        HexTownsPlugin.java
        api/                  # publiczne API (eksportowane przez ServicesManager)
        command/
        config/
        coop/
        database/
        listener/
        model/
        protection/
        service/
        ui/
        visual/               # /town check fake-block engine
      resources/
        plugin.yml
        config.yml
```

`build.gradle` — analogicznie do `DbExample`/`HexElimination`: shadow lub plain jar; `compileOnly` na Paper API; `depend: [HexCore]` w `plugin.yml`.

---

## 3. Integracja z HexCore

### 3.1 Pobranie API

```java
var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
if (reg == null) { disable(); return; }
this.hex = reg.getProvider();
```

Z HexCore wykorzystujemy:

| Komponent | Do czego |
|---|---|
| `hex.db()` (`DatabaseService` + `Db`) | persistencja miast, członków, claimów, meta, COOP requests |
| `hex.ui()` (`UiService`) | wszystkie komunikaty (MiniMessage), title/actionbar/sound, GUI tekst |
| `hex.regions()` (`RegionService`) | rejestracja regionu miasta (do innych pluginów które działają na regionach — opcjonalnie zostawiamy własny indeks chunkowy jako szybki lookup, a `Region` używamy do interopu) |
| `HexMessageBus` (`hex.core.api.messaging`) | broadcast eventów `towns.*` dla innych pluginów (reset stat, claim, destroy) |
| `hex.flags()` (`FeatureFlagService`) | feature flagi (np. `towns.invasion.enabled`) |
| `hex.coins()` / `rankingPoints()` | przyszłe money sinki za claim, sprawdzanie rangi |

### 3.2 Eksport własnego API

W `onEnable()` HexTowns rejestruje swój serwis:

```java
TownsApi townsApi = new TownsApiImpl(townService, claimService, coopService, eventBus);
Bukkit.getServicesManager().register(TownsApi.class, townsApi, this, ServicePriority.Normal);
```

Inne pluginy (Minions, Collections, Skills, Invasion, EQ) odczytują go tak samo jak `HexApi`.

---

## 4. API publiczne (`hex.towns.api`)

### 4.1 Interfejs `TownsApi`

API celowo **nie posiada** metody „daj listę wszystkich miast” — przy 10 000 miast taki zwrot
zaśmieca pamięć i zachęca pluginy do skanów liniowych. Zamiast tego oferujemy lookup po
kluczu, po graczu, po chunku i strumieniową paginację dla narzędzi admina.

```java
public interface TownsApi {
    // Hot-path — odpowiada O(1), trafia tylko do cache w pamięci
    Optional<Town> findTown(UUID townId);
    Optional<UUID> townIdAt(int chunkX, int chunkZ, String world); // surowy, najszybszy
    Optional<Town>  townAt(Chunk chunk);
    Optional<Town>  townAt(Location loc);
    Optional<UUID>  townIdOf(UUID playerId);     // członkostwo gracza (owner/coop)
    boolean isMember(UUID playerId, UUID townId);
    boolean isOwner(UUID playerId, UUID townId);

    boolean isProtected(Location loc);            // chunk należy do jakiegokolwiek miasta
    boolean canBuild(Player p, Location loc);     // główny test używany w listenerach

    // Strumieniowy odczyt (cursor-paginated) — wyłącznie dla narzędzi admina / migracji.
    // Nigdy nie ładuje wszystkich miast naraz.
    void forEachTown(java.util.function.Consumer<Town> visitor, int batchSize);
    Page<Town> listPage(String afterTownId, int limit);  // cursor = ostatnie id
    int countTowns();                                     // tani SELECT COUNT(*)

    // growth
    int  growthPoints(UUID townId);
    void addGrowthPoints(UUID townId, int delta, String source); // kolejkowane, batchowane

    // generic meta hub — patrz sekcja 11.
    String getMeta(UUID townId, String key, String def);
    int    getMetaInt(UUID townId, String key, int def);
    void   setMeta(UUID townId, String key, String value);
    java.util.Map<String,String> getMetaPrefix(UUID townId, String keyPrefix);

    // Rejestracja "namespace pluginu" — patrz sekcja 11 (Plugin Data Extension).
    TownDataNamespace dataNamespace(
        org.bukkit.plugin.Plugin owner,
        String namespace,
        TownDataResetHandler onReset
    );

    // event hooks (lokalne, dla pluginów wewnętrznych — Bukkit events i tak są emitowane)
    void registerListener(TownsListener listener);
}
```

`Town` w cache trzyma tylko lekki rdzeń (id, owner, world, heart, growth, createdAt). Listę
chunków i COOP-ów pobiera leniwie z indeksów pamięciowych (sekcja 9.2).

### 4.2 Model `Town`

```java
public final class Town {
    UUID id;
    UUID ownerId;
    String name;                 // nazwa wyświetlana (opcjonalna, MVP = nick właściciela)
    String world;
    ChunkPos heart;              // chunk centralny (gdzie postawiono "serce bazy")
    Set<ChunkPos> chunks;        // wszystkie chunki należące do miasta
    Set<UUID> coopMembers;       // max coopMembersCap-1 (bo owner się liczy)
    int growthPoints;
    Instant createdAt;
    // meta poprzez TownsApi#getMeta
}
```

### 4.3 Reguły członkostwa

- Gracz może być **w max 1 mieście** (rola `OWNER` lub `COOP`) — PRIMARY KEY na `uuid` w `town_members`.
- Gracz, który jest aktualnie w COOP-ie, **nie może** użyć `/town create`. Walidacja: lookup w `playerIndex` (in-memory) **przed** transakcją + UNIQUE constraint w DB jako safety net.
- Owner, który `/town accept`-uje COOP request od gracza, który już ma swoje miasto → odrzucamy: ten gracz musi najpierw `/town destroy`. Komunikat: `towns.coop.requester-has-town`.
- Symetrycznie: gracz z aktywnym miastem nie może wysłać `/town coop` w cudzym mieście.

### 4.4 Eventy Bukkit (`hex.towns.api.event`)

Każdy z `extends Event` (sync/async wg semantyki). To **kanoniczne źródło** dla innych pluginów:

- `TownCreatedEvent(Town, Player owner)`
- `TownDestroyedEvent(Town, Player by, List<UUID> affectedPlayers)` — **kluczowy** dla resetu statystyk
- `TownChunkClaimedEvent(Town, ChunkPos chunk, Player by)`
- `TownCoopJoinedEvent(Town, UUID player)`
- `TownCoopLeftEvent(Town, UUID player, LeaveReason reason)` — `RESIGN`, `KICKED`, `TOWN_DESTROYED`
- `TownPreBuildEvent(Cancellable, Player, Location)` — opcjonalny hook dla integracji (mining, area effects)

Po stronie `HexMessageBus` (dla pluginów niezwiązanych kompilacyjnie z HexTowns API) duplikujemy każdy event jako wiadomość:

```text
channel: "towns.created"           data: {townId, ownerUuid, world, heartChunkX, heartChunkZ}
channel: "towns.destroyed"         data: {townId, ownerUuid, members: [uuid,...], reason}
channel: "towns.chunk.claimed"     data: {townId, chunkX, chunkZ, byUuid}
channel: "towns.coop.joined"       data: {townId, uuid}
channel: "towns.coop.left"         data: {townId, uuid, reason}
channel: "towns.reset.requested"   data: {playerUuids: [...], reason}   <-- emit przy destroy/endcoop
channel: "towns.data.purge"        data: {townId, namespaces: [...]}    <-- emit przy destroy (sekcja 11)
```

**Reset statystyk**: HexTowns NIGDY nie kasuje danych innych pluginów. Publikuje `towns.reset.requested`,
a Skills/Collections/Minions/EQ subskrybują i czyszczą swoje tabele.

**Sprzątanie danych miasta**: przy `destroy` emitujemy też `towns.data.purge` z listą zarejestrowanych
namespace'ów (sekcja 11). Pluginy MUSZĄ zareagować, inaczej HexTowns zaloguje ostrzeżenie i ponowi event.

---

## 5. Schemat bazy danych (MySQL/MariaDB)

Wszystkie tabele przez `db.t("...")` (prefix konfiguralny przez HexCore). Silnik **InnoDB**,
`utf8mb4_bin`. Klucze są ID-numeryczne tam, gdzie mogą być duże (`town_chunks`, `town_meta`)
bo VARCHAR(36) jako PK marnuje I/O dla 10k miast × średnio 20 chunków = 200k wierszy w
`town_chunks`.

**Uwaga implementacyjna dla obecnego HexCore:** `Db` nie zwraca jeszcze generated keys, więc
pierwsza implementacja HexTowns używa `BIGINT UNSIGNED` wyliczanego deterministycznie z UUID
miasta (`uuid.msb ^ uuid.lsb`, maskowane do dodatniego `long`). Po dodaniu wsparcia
`insertReturningKey` w HexCore można wrócić do `AUTO_INCREMENT` bez zmiany publicznego API.

```sql
CREATE TABLE IF NOT EXISTS {p}towns (
  id            BIGINT UNSIGNED NOT NULL, -- obecnie deterministic z UUID; docelowo AUTO_INCREMENT
  uuid          BINARY(16) NOT NULL,         -- 36-znakowe UUID skompresowane do 16B
  owner_uuid    BINARY(16) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  world_id      SMALLINT UNSIGNED NOT NULL,  -- FK do {p}worlds (sekcja niżej)
  heart_cx      INT NOT NULL,
  heart_cz      INT NOT NULL,
  growth_points INT NOT NULL DEFAULT 0,
  created_at    BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_uuid (uuid),
  UNIQUE KEY uq_owner (owner_uuid),
  KEY idx_world_heart (world_id, heart_cx, heart_cz)  -- używane w paginacji admina
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}worlds (
  id    SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name  VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_name (name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}town_chunks (
  world_id SMALLINT UNSIGNED NOT NULL,
  cx       INT NOT NULL,
  cz       INT NOT NULL,
  town_id  BIGINT UNSIGNED NOT NULL,
  -- bucket = (cx >> 4, cz >> 4) = 16x16 chunks (256 chunkow). Indeks wystarcza do
  -- zapytania o sąsiedztwo bez skanu całej tabeli — patrz sekcja 8.1.
  bucket_x INT NOT NULL,
  bucket_z INT NOT NULL,
  PRIMARY KEY (world_id, cx, cz),
  KEY idx_town (town_id),
  KEY idx_bucket (world_id, bucket_x, bucket_z),
  CONSTRAINT fk_tc_town FOREIGN KEY (town_id) REFERENCES {p}towns(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}town_members (
  uuid      BINARY(16) NOT NULL,           -- PK: gracz w max 1 mieście
  town_id   BIGINT UNSIGNED NOT NULL,
  role      TINYINT UNSIGNED NOT NULL,     -- 0=OWNER, 1=COOP
  joined_at BIGINT NOT NULL,
  PRIMARY KEY (uuid),
  KEY idx_town (town_id),
  CONSTRAINT fk_tm_town FOREIGN KEY (town_id) REFERENCES {p}towns(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}town_meta (
  town_id BIGINT UNSIGNED NOT NULL,
  ns      VARCHAR(32)  NOT NULL,           -- namespace pluginu (sekcja 11)
  k       VARCHAR(96)  NOT NULL,
  v       VARCHAR(255) NOT NULL,           -- duże dane trzymaj we własnej tabeli pluginu
  PRIMARY KEY (town_id, ns, k),
  KEY idx_ns (ns),
  CONSTRAINT fk_tmeta_town FOREIGN KEY (town_id) REFERENCES {p}towns(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}town_coop_requests (
  town_id     BIGINT UNSIGNED NOT NULL,
  requester   BINARY(16) NOT NULL,
  created_at  BIGINT NOT NULL,
  PRIMARY KEY (town_id, requester),
  KEY idx_requester (requester),
  KEY idx_created (created_at),            -- TTL purge
  CONSTRAINT fk_tcr_town FOREIGN KEY (town_id) REFERENCES {p}towns(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS {p}data_namespaces (
  ns           VARCHAR(32) NOT NULL,
  plugin_name  VARCHAR(64) NOT NULL,
  registered_at BIGINT NOT NULL,
  PRIMARY KEY (ns)
) ENGINE=InnoDB;
```

### 5.1 Indeksy in-memory (kluczowe dla skali)

Na starcie ładujemy **wyłącznie** kompaktowe indeksy, NIE wszystkie meta/chunki w pełnej formie:

| Index | Klucz | Wartość | Rozmiar przy 10k miast |
|---|---|---|---|
| `chunkIndex` | `long packed(world_id<<48 \| cx<<24 \| cz)` | `long townId` | ~200k wpisów × 16B ≈ 3 MB |
| `playerIndex` | `UUID` (msb,lsb) | `long townId` | ~30k graczy × 24B ≈ 1 MB |
| `townCache` | `long townId` | `Town` (Caffeine LRU, max 5000) | ~5k × ~200B ≈ 1 MB |
| `bucketIndex` | `long packed(world_id, bucket_x, bucket_z)` | `Set<long townId>` | używany tylko do distance-check |

`bucketIndex` zapewnia, że `/town create` test odległości **iteruje tylko 2–3 bucketów wokół
gracza**, nie 10k miast. Bucket = 16 chunków; promień 10ch mieści się w 2×2 bucketach.

### 5.2 Cache w pamięci (`Caffeine` lub własna LRU)

- `townCache` — `townId -> Town`, max **5000** wpisów (z headroom), TTL 10 min, refresh-after-write 1 min. Miasta graczy online są **pinowane** (held strong) póki gracz jest online.
- `members(townId)` — leniwy load z indeksu odwrotnego (lista UUID per town); cache LRU 1000.
- `chunksOf(townId)` — listę chunków miast cudzych zwykle nie potrzebujemy; trzymamy w pamięci tylko dla miast pinowanych.

### 5.3 Connection pool & batching

- HexCore eksponuje HikariCP pool — HexTowns rekomenduje (do udokumentowania w README HexCore): `maximumPoolSize ≥ 20`, `connectionTimeout 3000ms`, `leakDetection 10000`.
- `growth_points` updates kolejkowane przez `GrowthQueue` i flushowane co `1 s` jednym `INSERT ... ON DUPLICATE KEY UPDATE` batch'em (do 500 wierszy) — chroni przed N updateów per sekunda.
- `town_meta.set(...)` ma write-coalescing per-klucz w oknie 250 ms.
- Wszystkie zapisy idą przez `hex.db().asyncRun(...)`; nigdy z głównego wątku.

---

## 6. Konfiguracja (`config.yml`)

Trzymamy **tylko parametry logiki** (zgodnie z konwencją HexCore — teksty są w UI namespace `towns`):

```yaml
towns:
  world-whitelist: ["world"]            # światy, w których można zakładać miasta

  size:
    initial-radius: 1                   # 1 = 3x3, 2 = 5x5, ...
    max-chunks: 49                      # twardy limit łącznej liczby chunków
    buffer-chunks-between-towns: 1      # zawsze >= 1 (pusty chunk między miastami)

  creation:
    min-distance-chunks: 10             # promień blokady od istniejącego miasta
    cost-coins: 0                       # money sink (opcjonalny)
    confirm-required: true              # GUI/chat z ostrzeżeniem o resecie przy odejściu

  coop:
    max-members: 3                      # razem z właścicielem
    request-ttl-seconds: 120

  protection:
    block-place: true
    block-break: true
    interact-containers: true
    interact-doors: false
    pvp: false                          # SafeZone
    explosion: true
    fire-spread: true
    keep-inventory-pve: false           # do decyzji wg PDF
    item-pickup-window-seconds: 60      # tylko owner/coop może podnieść

  growth:
    starting-points: 0
    # źródła growth points (np. kolekcje, questy) dodają punkty przez TownsApi#addGrowthPoints

  visual-check:                          # /town check
    enabled: true
    block: "TERRACOTTA"                  # nazwa Material; konfigurowalne
    color-by-relation:                   # mapowanie relacji -> kolor terakoty
      own:        "LIME_TERRACOTTA"
      coop:       "CYAN_TERRACOTTA"
      other:      "RED_TERRACOTTA"
      enemy-near: "ORANGE_TERRACOTTA"    # przyszłe inwazje
    radius-chunks: 6                     # MVP: max 6 (~13x13 chunków = ~169 chunków/gracza)
    refresh-ticks: 40                    # co 2s; przy 1000 online ogranicza pakiety
    mode: "frame"                        # surface | frame — frame = tylko granica chunka (mniej pakietów)
    max-blocks-per-tick-global: 20000    # globalny budżet pakietów per tick

  destroy:
    confirm-window-seconds: 30
    publish-reset-event: true            # broadcast HexMessage `towns.reset.requested`
    purge-grace-seconds: 60              # ile czekamy aż pluginy posprzątają swoje dane (sekcja 11)

  cache:
    town-cache-size: 5000                # Caffeine LRU; miasta online graczy pinowane
    member-cache-size: 1000
    metrics-enabled: true                # /town admin metrics: hit-rate, db-latency, queue-depth

  scale:
    growth-flush-interval-ms: 1000       # batch DB writes dla growth points
    meta-flush-interval-ms: 250          # write-coalescing dla town_meta
    coop-request-purge-interval-seconds: 300
    distance-check-bucket-size: 16       # rozmiar bucketu (chunkow) dla bucketIndex

database:
  table-prefix: "towns_"                  # nadpisywalne; HexCore i tak ma globalny prefix
```

Hot-reload przez `/town admin reload` (uprawnienie `hextowns.admin`).

---

## 7. Komendy

> Komendy listowe (`/town list`, globalna mini-mapa) zostały **usunięte / zmienione** —
> przy 10 000 miast nie mają wartości dla gracza i obciążają serwer. Wszystko, czego gracz
> potrzebuje w kontekście świata, jest **lokalne** (chunk, w którym stoi).

### 7.1 Komendy z wymagań

| Komenda | Permisja | Opis |
|---|---|---|
| `/town create [nazwa]` | `hextowns.use` | Zakłada miasto na chunku gracza (3×3, walidacja odległości 10ch, chat-confirm z ostrzeżeniem o resecie statystyk). Blokowane jeśli gracz jest w COOP. |
| `/town claim` | `hextowns.use` | Claimuje chunk, na którym stoi gracz, jeśli przylega do jego miasta i nie łamie buforu. Wymaga ≥1 growth pointa, zużywa go. |
| `/town coop` | `hextowns.use` | W cudzym mieście wysyła prośbę o COOP do właściciela. Blokowane jeśli proszący ma własne miasto. |
| `/town accept <nick>` | `hextowns.use` | Właściciel akceptuje request COOP (musi stać w swoim mieście, miejsce w COOP, kandydat bez własnego miasta). |
| `/town endcoop` | `hextowns.use` | COOP-owicz odchodzi; 2-stopniowe potwierdzenie + ostrzeżenie o resecie. |
| `/town destroy` | `hextowns.use` | Owner; 2-stopniowe potwierdzenie; emituje `TownDestroyedEvent` + `towns.reset.requested` + `towns.data.purge`. |
| `/town check` | `hextowns.use` | Toggle wizualizacji terakotowej; działa per-gracz (fake blocks, brak side-effectów). |

### 7.2 Dodatkowo proponowane (sensowne przy skali 10k miast)

| Komenda | Opis |
|---|---|
| `/town info` | Bez argumentu: info o **moim** mieście (owner, coop, chunki, growth, meta z pluginów). Tani lookup po `playerIndex`. |
| `/town info <nick>` | Info o mieście gracza po nicku → przez UUID resolver Bukkit. Brak skanu bazy. |
| `/town here` | Czyje miasto pokrywa aktualny chunk + dystans do najbliższej granicy mojego miasta. |
| `/town map` | Tekstowa mini-mapa **9×9 chunków** wokół gracza (już lokalne, O(81) w `chunkIndex`). |
| `/town kick <nick>` | Owner wyrzuca COOP-owicza (emituje `TownCoopLeftEvent` z reason=`KICKED` + `towns.reset.requested`). |
| `/town leave` | Alias `endcoop`. |
| `/town transfer <nick>` | Properties: nowy właściciel musi być w COOP tego miasta i online. 2-stopniowe potwierdzenie. |
| `/town sethome` / `/town home` | Home w obrębie miasta (członkowie). |
| `/town setname <nazwa>` | Zmiana nazwy. Walidacja: regex `[A-Za-z0-9_]{3,32}`, unikalność via UNIQUE w DB (jeśli wymagane). |
| `/town growth` | Podgląd punktów rośnięcia i top-5 źródeł (`growth.source.*` w meta). |
| `/town invite <nick>` | Alternatywa dla `coop` — owner zaprasza. Wymaga, by zapraszany nie miał miasta. |

**Usunięte / zmienione vs poprzednia wersja planu:**

- ❌ `/town list [page]` — usunięte. Przy 10 000 miast nieczytelne i zachęca do scrapingu. Zastąpione `/town admin list` (uprawnienie admina, cursor pagination).
- 🔁 `/town map` — pozostaje, ale jest **lokalne** (9×9 wokół gracza), nigdy nie pokazuje globalnej mapy.

### 7.3 Komendy admina (skala-aware)

| Komenda | Opis |
|---|---|
| `/town admin reload` | Hot-reload configu + ponowna rejestracja UI defaults. |
| `/town admin list [cursor]` | **Cursor pagination** po `towns.id` (limit 50). Nigdy SELECT *. |
| `/town admin search <nick\|name>` | Indexed lookup (UNIQUE owner, UNIQUE name lub LIKE prefix z limit 20). |
| `/town admin delete <town>` | Awaryjne usunięcie. Pełny cykl event/purge jak `destroy`. |
| `/town admin tp <town>` | Tp do serca miasta. |
| `/town admin setgrowth <town> <n>` | Debug. |
| `/town admin metrics` | Cache hit-rate, średnie latency DB, głębokość kolejek growth/meta, aktywne `/town check` sessions. |
| `/town admin purge-orphans` | Skanuje `data_namespaces` × `towns` i emituje `towns.data.purge` dla osieroconych ns (sekcja 11.4). |

Wszystkie potwierdzenia korzystają z `UiService` + clickable MiniMessage (`<click:run_command:'/town destroy confirm'>`).

---

## 8. Algorytmy

### 8.1 `/town create` (O(k) gdzie k = chunki w 2×2 bucketach)

1. Walidacja: `playerIndex.get(uuid) == null` (gracz nie ma miasta i nie jest w COOP — patrz 4.3), świat na whitelist.
2. Pobierz chunk gracza `(cx, cz)`. Wylicz `chunks0 = square(cx, cz, radius=initial-radius)`.
3. **Test odległości — bucketowany**:
   - Wyznacz `bucket_x = cx >> 4`, `bucket_z = cz >> 4` oraz `radiusBuckets = ceil(min-distance-chunks / 16)+1`.
   - Pobierz `candidateTownIds = union(bucketIndex.get(bx, bz))` dla wszystkich bucketów w okręgu.
   - Dla każdego `townId` sprawdź czy jakikolwiek z jego chunków (`chunksOf(townId)`, leniwie ładowany na żądanie) spełnia `max(|dx|,|dz|) <= 10` względem `chunks0`.
   - Krótszy path: jeśli `candidateTownIds` puste → akceptuj od razu.
   - **Koszt**: w typowym świecie ~1–10 kandydatów; nigdy nie iterujemy po 10k miast.
4. **Money sink** (opcjonalnie z configu): pobierz coins przez `hex.coins()`.
5. **Confirm** (`creation.confirm-required`): chat-confirm z `<click:run_command>` do `/town create confirm <token>`. Token w pamięci, TTL 30 s, single-use.
6. Transakcja DB (`db.tx(...)`): `INSERT towns`, `INSERT town_members(role=0)`, **batch INSERT** 9× `town_chunks` (jeden `INSERT ... VALUES (...),(...),...`), opcjonalny init `town_meta`.
7. Update `chunkIndex`, `playerIndex`, `bucketIndex`, `townCache.pin(townId)` (bo owner online).
8. Emit `TownCreatedEvent` + `HexMessage("towns.created")` (async tasks).
9. UI: title + sound success.

### 8.2 `/town claim`

1. Gracz musi należeć do miasta (owner lub coop).
2. Stoi na chunku `c`. `c` **nie należy** do żadnego miasta.
3. **Adjacency**: istnieje sąsiad 4-kierunkowy `c` należący do mojego miasta. (Diagonal opcjonalnie — MVP: tylko orthogonal.)
4. **Bufor**: dla każdego chunka cudzego miasta `o` zachodzi `max(|c.x-o.x|, |c.z-o.z|) >= buffer+1` (z `buffer=1` daje min. 1 pusty chunk).
5. Limit: `town.chunks.size() < max-chunks`.
6. Growth: `town.growthPoints >= 1` → dekrement.
7. Insert `town_chunks`, update chunkIndex, event `TownChunkClaimedEvent` + `HexMessage`.

### 8.3 COOP

- `/town coop` w cudzym mieście → upsert `town_coop_requests`, powiadomienie ownera (`api.ui().send(owner, "towns.coop.request", ...)`), TTL.
- `/town accept <nick>`: weryfikacja, że owner stoi we własnym mieście, liczba członków `< max-members`, request istnieje. Insert `town_members(role=COOP)`, event.
- `/town endcoop`: 2-step confirm → delete `town_members`, event z `reason=RESIGN`, broadcast `towns.reset.requested` z `playerUuids=[uuid]`.

### 8.4 `/town destroy`

- Tylko owner. 2-step (`/town destroy` → MiniMessage z `<click:run_command:'/town destroy confirm <token>'>`, token TTL = `confirm-window-seconds`).
- Pobierz wszystkich członków (owner + coop) i listę zarejestrowanych `data_namespaces`.
- **Faza 1 (sync, in-memory)**: oznacz miasto jako `DESTROYING` w `townCache` (blokuje claim/coop/build), wyczyść `chunkIndex`/`playerIndex`/`bucketIndex` dla tego id.
- **Faza 2 (async)**: emit `TownDestroyedEvent` + `HexMessage("towns.destroyed")` + `HexMessage("towns.reset.requested")` + `HexMessage("towns.data.purge", {townId, namespaces})`. Czekaj do `destroy.purge-grace-seconds` aż pluginy ACK-ują przez `townDataNamespace.acknowledgePurge(townId)`. Brakujące ACK → log warning + zaplanowany retry przez `/town admin purge-orphans`.
- **Faza 3 (async)**: `DELETE FROM towns WHERE id=?` — kaskada czyści `town_chunks` / `town_members` / `town_meta` / `town_coop_requests`. Wykonywane **po** grace, żeby pluginy mogły dokończyć referencyjne odczyty.
- Owner i COOP-owicze są wyrejestrowani z `playerIndex` — mogą natychmiast `/town create`.

### 8.5 `/town check` — wizualizacja

- Per-gracz stan w pamięci: `toggleOn[uuid] : { Set<ChunkPos> overriddenChunks }`.
- Co `refresh-ticks` (task async-safe, ale `sendBlockChanges` musi być sync) iterujemy chunki w promieniu `radius-chunks` wokół gracza. Dla każdego chunka, który należy do jakiegoś miasta, wysyłamy `Player#sendBlockChanges(...)` z listą fałszywych bloków terakoty (kolor wg relacji do gracza — own/coop/other).
- Strategia bloków do podmiany: tylko **najwyższy nieprzezroczysty blok kolumny** (1 blok per (x,z)), żeby nie zżerać RAM i nie wprowadzać dezorientacji. Alternatywnie: ramka na granicy chunka (4 słupy) — do parametru `visual-check.mode: surface | frame`.
- Drugi `/town check` → wyzeruj overridy, dla każdego (x,y,z) wyślij `world.getBlockAt(x,y,z).getState().getBlockData()` żeby przywrócić oryginał.
- Auto-cleanup przy `PlayerQuitEvent` i przy zmianie świata.
- Brak modyfikacji świata — **wyłącznie pakiety klienta**.

### 8.6 Ochrona (SafeZone)

`hex.towns.protection.TownProtectionListener` (priority HIGH, ignoreCancelled false) słucha:

- `BlockPlaceEvent`, `BlockBreakEvent`
- `PlayerInteractEvent` (containers, doors wg configu)
- `EntityDamageByEntityEvent` (PvP off w mieście)
- `EntityExplodeEvent`, `BlockExplodeEvent`
- `BlockSpreadEvent` (fire)
- `PlayerBucketEmptyEvent` / `Fill`
- `HangingBreakByEntityEvent`

Każdy delegowany do `TownsApi#canBuild(player, loc)`:
```java
Optional<Town> t = api.townAt(loc);
if (t.isEmpty()) return true;
return api.isMember(player.getUniqueId(), t.get().id());
```

Pickup itemów po śmierci (PDF): nasłuch `PlayerDropItemEvent` z markerem PDC `owner-uuid` + `expire-at`; `PlayerAttemptPickupItemEvent` blokuje obcych w oknie czasowym z configu.

---

## 9. Wątki, I/O i skala (1000 online / 10000 miast)

### 9.1 Reguły wątkowania

- Wszystkie odczyty hot-path (`townAt`, `canBuild`, `isMember`, `townIdOf`) → **lock-free**, czyste lookupy w `ConcurrentHashMap`. Bezpieczne z dowolnego wątku (przydatne dla async chat / Folia-ready).
- Wszystkie zapisy do DB przez `hex.db().asyncRun(...)` — nigdy z main thread.
- Stan tranzycyjny (np. `DESTROYING`, blokady claim) trzymany jako `volatile` flagi w `Town` + krótkie `synchronized` na `townId` (`StampedLock` per-bucket dla `bucketIndex`).
- `PlayerMoveEvent` używa `townIdAt(chunkX, chunkZ, world)` — czysta operacja na long-keyed mapie, **żadnych** alokacji w hot-loopie.

### 9.2 Lazy loading

- Na starcie pluginu ładujemy z DB **tylko** to, czego potrzebuje hot-path: `playerIndex`, `chunkIndex`, `bucketIndex`. To ~250 k wierszy łącznie — `SELECT ... STREAM` (jdbc fetch-size 1000) w batchach. Czas startu cel: **<5 s** dla 10k miast.
- `Town` w pełnej postaci (członkowie, meta) ładujemy on-demand do `townCache`. Miasto inactive (offline owner) jest evictowane po 10 min idle.
- Lista chunków miasta cudzego ładowana wyłącznie gdy `/town create` distance-check ma kandydata (sekcja 8.1).

### 9.3 Batching i write-coalescing

- `growth_points` updates → ring-buffer kolejka, flush co `growth-flush-interval-ms` jednym `INSERT INTO ... ON DUPLICATE KEY UPDATE growth_points=growth_points+VALUES(growth_points)` (do 500 wierszy).
- `town_meta.set(...)` → coalescing per-(townId, ns, key) w oknie `meta-flush-interval-ms`. Wielokrotne ustawienie tego samego klucza w 250ms = jeden DB write.
- `town_coop_requests` TTL purge co 5 min: `DELETE WHERE created_at < ?`.
- Cleanup `town_coop_requests` przy starcie (drop stale).

### 9.4 Budget pakietów dla `/town check`

- Globalny semafor `max-blocks-per-tick-global` (np. 20k bloków/tick). Per-gracz round-robin scheduler.
- Tryb `frame` (zalecany default) — tylko 4 słupy granicy chunka × wysokość = ~64 bloki/chunk. Dla `radius=6` → ~169 chunków × 64 = ~10k pakietów na pełne odświeżenie (rozsmarowane na 2 sek).
- Tryb `surface` — domain bardziej drogi; w MVP off.
- `/town check` automatycznie wyłącza się po `300 s` braku ruchu gracza (`PlayerMoveEvent` flag).

### 9.5 SLO i metryki

`/town admin metrics` raportuje (Prometheus-compatible w przyszłości):

- `townCache` hit-rate (cel: >95%)
- średnie latency `townAt()` (cel: <50 µs)
- średnie latency `db.tx` create/destroy (cel: <50 ms)
- głębokość kolejek growth/meta (alert: >10k)
- liczba aktywnych `/town check` sesji + bloków/tick

---

## 10. UI — szablony MiniMessage (namespace `towns`)

Rejestrowane w `onEnable()` przez `api.ui().registerDefaults("towns", Map.of(...))`. Przykładowe klucze (treści można potem nadpisać w `ui.yml > overrides`):

```
towns.create.success           "<green>Założono miasto <yellow><town></yellow>.</green>"
towns.create.too-close         "<red>Za blisko innego miasta (min. <distance> chunków).</red>"
towns.create.confirm           "<gold>Założenie miasta oznacza, że rezygnacja zresetuje twoje statystyki. </gold><click:run_command:'/town create confirm'><green>[POTWIERDŹ]</green></click>"
towns.claim.success            "<green>Zaclaimowano chunk (<cx>, <cz>).</green>"
towns.claim.no-growth          "<red>Brak punktów rośnięcia.</red>"
towns.claim.buffer-violation   "<red>Ten chunk sąsiaduje z cudzym miastem — wymagany 1 chunk przerwy.</red>"
towns.claim.not-adjacent       "<red>Chunk nie przylega do twojego miasta.</red>"
towns.claim.limit-reached      "<red>Osiągnięto maksymalny rozmiar miasta (<max> chunków).</red>"
towns.coop.request-sent        "<aqua><player></aqua> prosi o dołączenie do twojego miasta. <click:run_command:'/town accept <player>'><green>[AKCEPTUJ]</green></click>"
towns.coop.full                "<red>Miasto ma już maksymalną liczbę członków.</red>"
towns.destroy.warn             "<red>UWAGA: zniszczenie miasta zresetuje statystyki tobie i graczom w COOP.</red> <click:run_command:'/town destroy confirm <token>'><dark_red>[POTWIERDŹ]</dark_red></click>"
towns.endcoop.warn             "<red>UWAGA: odejście z COOP zresetuje twoje statystyki.</red> <click:run_command:'/town endcoop confirm'><dark_red>[POTWIERDŹ]</dark_red></click>"
towns.protect.no-build         "<red>Nie możesz tu budować — to teren miasta <yellow><owner></yellow>.</red>"
towns.check.on                 "<green>Podgląd miast: WŁĄCZONY.</green>"
towns.check.off                "<gray>Podgląd miast: wyłączony.</gray>"
```

Tytułowe okna potwierdzeń są zwykłym chatem z `<click:run_command>` (brak konfliktów z plug-and-play GUI).

---

## 11. Plugin Data Extension — generyczny hub i lifecycle (kluczowe)

Każdy plugin SMP (Minions, Collections, Skills, Invasion, TownPerks) chce **dopinać własne**
statystyki/limity per-miasto. Wymagania użytkownika:

- HexTowns nie zna tych pluginów,
- ALE przy `destroy` ich dane muszą zniknąć, inaczej baza puchnie i limity „niby zużyte” blokują nowo zakładane miasta tych samych graczy.

Dlatego HexTowns udostępnia **kontrakt namespace'ów danych miasta**.

### 11.1 Trzy poziomy integracji (rosnąca odpowiedzialność / wydajność)

| Poziom | Kiedy używać | Mechanizm | Cleanup przy destroy |
|---|---|---|---|
| A. **Meta-KV** | Małe wartości (limit minionów, flagi, liczniki <255 znaków) | `townsApi.setMeta(townId, ns, key, value)` w tabeli `town_meta` | **Automatyczny** — kaskada FK |
| B. **Linked-Table** | Własna tabela pluginu z dużymi danymi (np. `minions`, `town_collections_progress`) | Plugin tworzy tabelę z `town_id BIGINT UNSIGNED NOT NULL` + `FOREIGN KEY ON DELETE CASCADE REFERENCES {p}towns(id)` | **Automatyczny** — kaskada DB |
| C. **External-Store** | Dane w innym systemie (Redis, oddzielny schemat, plik) | Plugin rejestruje `TownDataNamespace` z `TownDataResetHandler` | **Reaktywny** — przez `towns.data.purge` event |

**Zasada**: jeśli da się A lub B (90% przypadków) — używamy. C tylko gdy naprawdę musimy.

### 11.2 Rejestracja namespace'u

```java
TownDataNamespace ns = townsApi.dataNamespace(this, "minions", (townId, members) -> {
    // wywoływane przy destroy/admin-purge — synchronicznie z grace-period
    minionRepository.deleteByTownId(townId);   // tu plugin czyści wszystko swoje
    return CompletableFuture.completedFuture(null);
});
```

Po wywołaniu:
- HexTowns insert/update w `data_namespaces` (`ns="minions", plugin_name="HexMinions"`).
- Przy `destroy` namespace dostaje event + ACK callback.
- Plugin może zarejestrować się **tylko raz na cały lifecycle serwera**; ponowna rejestracja po reload jest idempotent.

### 11.3 Kontrakt cleanup

Kolejność przy `destroy`:

1. `TownDestroyedEvent` (Bukkit) — pluginy zależne kompilacyjnie reagują **synchronicznie**.
2. `towns.data.purge` (HexMessageBus) — luźno spięte pluginy dostają `townId` + listę swoich ns.
3. Wszystkie `TownDataResetHandler` (level C) są wywoływane równolegle, czekamy do `destroy.purge-grace-seconds` na ACK.
4. Dopiero potem `DELETE FROM towns WHERE id=?` — co kaskaduje A i B.
5. Brakujące ACK → wpis do `towns.orphan-log` (plik), retry przez `/town admin purge-orphans`.

### 11.4 Anty-zaśmiecanie — `purge-orphans`

Admin / scheduled task uruchamia okresowo (np. co 1 h):

- Dla każdego `ns` w `data_namespaces`, wywołuje `handler.scan(allKnownTownIds)` (cursor).
- Każdy plugin sam decyduje, czy ma rekordy bez odpowiednika w `towns` i je kasuje.
- Default impl: jeśli plugin używa poziomu B, można wygenerować skan SQL automatycznie:
  ```sql
  DELETE p FROM {plugin_table} p
  LEFT JOIN {p}towns t ON t.id = p.town_id
  WHERE t.id IS NULL
  ```

To gwarantuje: **żaden plugin nie może osierocić danych dłużej niż 1 godzinę**, niezależnie
od tego, czy padł grace-period czy plugin się crashował.

### 11.5 Reset statystyk gracza

Reset stat gracza po `endcoop`/`destroy` jest osobnym sygnałem (`towns.reset.requested`) i
**nie pokrywa się** z `towns.data.purge`. Powód: meta miasta (np. minion count w `town_meta`) jest
sprzątana automatycznie, ale statystyki osobiste gracza (skille, kolekcje per-player) wymagają
akcji pluginu owner-of-data.

### 11.6 Przykładowe namespace'y do uzgodnienia z ekosystemem

| Namespace | Plugin | Typ | Klucze meta (przykład) |
|---|---|---|---|
| `minions` | HexMinions | A + C | `limit`, `unlocked_recipes` (CSV ID) |
| `collections` | HexCollections | B | własna tabela `town_collection_progress(town_id, collection_id, amount)` |
| `skills` | HexSkills | (per-player, nie per-town) | — |
| `perks` | HexTownPerks | A | `tree.mining.lvl`, `tree.combat.lvl` |
| `invasion` | HexInvasion | A + B | `last_invaded_at`, `defends_today`, tabela `invasion_log` |
| `home` | HexTowns (wbudowane) | A | `home.x`, `home.y`, `home.z`, `home.yaw` |

---

## 12. Współistnienie z `RegionService` HexCore

Dla interopu (np. WaterDrawn, AreaEffects mogą chcieć wiedzieć o regionach miast):

- Przy każdym claim/destroy `upsert/delete` w `RegionService` z `RegionKey("towns", townId.toString())`,
- min/max wyliczane jako AABB z `chunks` (Y = full world height),
- `meta` regionu zawiera `ownerUuid`, `pvp=false`, itd.

Plugin czyta jednak głównie własny chunkIndex — `RegionService` jest tylko **broadcast widoku**.

---

## 13. Bezpieczeństwo i edge-cases

- Wszystkie SQL — parametryzowane (przez `Db#update/query`), zero konkatenacji wartości.
- Race condition `claim` (dwóch graczy klika równolegle): operacja w `db.tx(...)` z UNIQUE constraint na `(world_id, cx, cz)` jako last-line-of-defense; w pamięci `StampedLock` per-bucket.
- Race `create` (dwóch graczy w tym samym tiku): UNIQUE na `town_members.uuid` i `towns.owner_uuid` — drugi insert leci wyjątkiem, klient dostaje `towns.create.race-conflict`.
- Race `coop accept` × `create` requestera: w transakcji najpierw `SELECT ... FOR UPDATE` na `town_members.uuid` requestera; jeśli istnieje wiersz → odrzucamy.
- Crash w trakcie create: brak rekordów cząstkowych dzięki transakcji.
- Crash w trakcie destroy (między fazą 2 a 3): przy starcie plugin skanuje `towns` z flagą `destroying=1` w meta i wznawia purge (lub `/town admin purge-orphans`).
- Świat usunięty z whitelisty — miasta tam istniejące są tylko-do-odczytu (zakaz claim/coop).
- Migracje schematu: tabela `{p}schema_version (version INT)`. Migration runner uruchamiany w `onEnable()` przed ładowaniem indeksów.
- `/town check` na laggu: globalny budżet (sekcja 9.4) + auto-disable przy braku ruchu.
- Czyszczenie pamięci przy `PlayerQuitEvent`/`PluginDisableEvent`.
- Folia-readiness: hot-path nie używa `Bukkit.getOnlinePlayers()` w pętlach; cache i indeksy są thread-safe.
- Backup: HexCore powinien dostarczyć narzędzie `/hexcore dbdump towns` — całość danych miasta da się odtworzyć z tabel `towns`, `town_chunks`, `town_members`, `town_meta`.

---

## 14. Roadmap iteracji

**Iteracja 1 (MVP, ten plan)**
- Sekcje 1–10 + 12; brak inwazji; reset stat = tylko event.

**Iteracja 2**
- Inwazje: nowy plugin `HexInvasion` zależny od `HexTowns` przez `TownsApi`. Dodaje „serce bazy” (entity/blok), tryb PvP-on-demand, blokadę modyfikacji bloków w trakcie inwazji. HexTowns dostaje hook `TownPreInvasionEvent` i flagę `invasion-active` w `town_meta`.
- Custom TNT — osobny plugin, korzysta z `TownsApi.isProtected` żeby nie niszczyć terenu w miastach.

**Iteracja 3**
- Growth points spinane z kolekcjami/rangami,
- `/town transfer`, persystentny home,
- `RankingPointsService` jako booster limitów (VIP/SVIP/Elita: `max-chunks`, dzienne invasions).

---

## 15. Checklist dla agenta wykonującego

1. [ ] Utwórz moduł `Plugins/HexTowns` z `build.gradle` (na wzór `DbExample`).
2. [ ] `plugin.yml` z `depend: [HexCore]`, komendami i permisjami `hextowns.use`, `hextowns.admin`.
3. [ ] `HexTownsPlugin#onEnable`: pobierz `HexApi`, utwórz repozytoria, migracje schematu (`schema_version`), załaduj **tylko** kompaktowe indeksy (`chunkIndex`, `playerIndex`, `bucketIndex`), zarejestruj listenery, komendy, `TownsApi` w `ServicesManager`, defaults UI namespace `towns`.
4. [ ] Zaimplementuj `TownService` (cache Caffeine), `ClaimService` (StampedLock per-bucket), `CoopService`, `VisualCheckService` (budget per-tick), `TownProtectionListener` (priority HIGH).
5. [ ] Wszystkie komunikaty przez `api.ui()`; żadnych `sendMessage("§...")`.
6. [ ] Wszystkie DB operacje przez `hex.db().db()` z `db.t("...")`; pisma asynchroniczne; batch dla growth/meta; UUID jako BINARY(16); światy znormalizowane do `world_id`.
7. [ ] Emituj Bukkit eventy z `hex.towns.api.event.*` ORAZ `HexMessage` na bus dla każdego z nich (`towns.created/destroyed/chunk.claimed/coop.*/reset.requested/data.purge`).
8. [ ] `/town destroy` i `/town endcoop` muszą publikować `towns.reset.requested` z listą UUID — bez kasowania cudzych danych. `/town destroy` dodatkowo publikuje `towns.data.purge` z listą `data_namespaces` i czeka grace.
9. [ ] Walidacja owner/coop: gracz w COOP NIE może `/town create`; gracz z miastem NIE może `/town coop`; `/town accept` weryfikuje, że kandydat nie ma miasta.
10. [ ] `/town check` używa wyłącznie `Player#sendBlockChanges` — nigdy `World#setBlock*`. Globalny budżet pakietów.
11. [ ] Brak `/town list` w komendach gracza. Tylko `/town admin list` z cursor pagination.
12. [ ] Brak metod typu `listAllTowns()` w API publicznym; `forEachTown(Consumer, batch)` + `listPage(cursor, limit)` only.
13. [ ] `TownDataNamespace` API zaimplementowane, `/town admin purge-orphans` działa.
14. [ ] Smoke test:
      - 1000 syntetycznych graczy + 10k miast w DB → start serwera <5 s, `townAt` p99 <100 µs.
      - 2 graczy, 2 miasta, próba claim na granicy buforu = blok.
      - destroy miasta → `town_meta`, `town_chunks`, `town_members` puste; namespace plugin dostaje purge + sprząta swoją tabelę; ponowne `create` przez tego samego gracza działa bez przeniesionych limitów.

---

## 16. Pytania do potwierdzenia przed implementacją

- Czy `name` miasta ma być wymagane przy `/town create`, czy domyślnie nick właściciela?
- Czy `keep-inventory-pve` (PDF: drop dostępny dla owner/coop w oknie) ma być w MVP czy iteracji 2?
- Czy `RankingPointsService` w HexCore już udostępnia rangi VIP/SVIP/Elita potrzebne do limitów, czy stub do tej pory?
- Czy `RegionService` ma być źródłem prawdy dla innych pluginów, czy wystarczy `TownsApi#townAt`?
- Czy HexCore HikariCP pool ma już ustawione `maximumPoolSize >= 20` (wymagane dla 1000 online)? Jeśli nie — dodać do README HexCore.
- Czy nazwa miasta powinna być UNIQUE globalnie (UX: easier `/town info <name>`) czy tylko per-owner?

