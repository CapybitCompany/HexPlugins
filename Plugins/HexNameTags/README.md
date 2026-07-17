# HexNameTags

Packetowy system etykiet nad graczami/entity dla Paper/Purpur 1.21.1.

## Cel

- Bez ArmorStandów.
- Bez ArmorStandów i bez Bukkitowych hologramów.
- Domyślnie bez mounta/passengera: fake `TextDisplay` podąża za graczem przez rzadkie packetowe pozycjonowanie z client-side interpolation (`teleportDuration`).
- Fake entity jest tylko po stronie konkretnego viewera; nie istnieje jako normalna Bukkit entity w świecie.
- Persistencja tagów graczy działa przez `HexCore` -> `HexApi` -> `DatabaseService`.


## Płynny efekt nad głową

Domyślny tryb renderowania to teraz:

```yml
rendering:
  mode: interpolated-follow
  movement-update-interval-ticks: 2
  teleport-duration-ticks: 2
```

Flow renderowania jest taki:

```text
1. Viewer dostaje spawn packet fake TextDisplay.
2. Viewer dostaje metadata tekstu/style, w tym Display.teleportDuration.
3. Co kilka ticków viewer dostaje packet pozycji TextDisplay nad głową targetu.
4. Klient interpoluje ruch displaya, więc wizualnie nie ma skokowego teleportu.
```

To nie jest ArmorStand, nie jest normalna Bukkit entity w świecie i w domyślnym trybie nie jest mountem/passengerem. `refresh-interval-ticks` służy tylko do sprawdzania widoczności/dystansu/zmian tekstu. Ruch kontrolują ustawienia `rendering.movement-update-interval-ticks` oraz `rendering.teleport-duration-ticks`.

Tryb `packet-text-display-passenger` został zostawiony jako awaryjny, ale u Was jednorazowy passenger nie śledził gracza, a cykliczne ponawianie `SetPassengers` powodowało widoczny remount/skok.

W pełni dowolny wieloliniowy tag nad prawdziwym graczem nie jest możliwy samym vanilla scoreboardem bez dodatkowego packetowego displaya. Scoreboard/TAB może dać prefix/suffix i ewentualnie below-name objective, ale nie dowolne stackowane linie.

## Wymagania

- Java 21
- Paper/Purpur 1.21.1
- `HexCore` z aktywnym `DatabaseService`
- PacketEvents zainstalowany jako osobny plugin (`packetevents`) w folderze `plugins/`

`plugin.yml` ma `depend` na `HexCore` i `packetevents`, więc HexNameTags startuje dopiero po nich.

## Budowanie

W paczce roboczej `HexNameTags` leży obok `HexCore`, dlatego `build.gradle` używa:

```gradle
compileOnly files('../HexCore/build/libs/HexCore.jar')
```

Budowanie:

```bash
./gradlew build
```

albo, jeśli używacie globalnego Gradle:

```bash
gradle build
```

Wynikowy jar:

```text
build/libs/HexNameTags-1.2.3.jar
```

W tej paczce jest też gotowy JAR `build/libs/HexNameTags-1.2.3.jar` wygenerowany z aktualnych źródeł.

## Komendy administracyjne

Główna komenda:

```text
/hexnametag
```

Wymaga permisji:

```text
hexnametags.admin
```

Domyślnie permisję ma OP.

### Pomoc

```text
/hexnametag help
```

Pokazuje listę komend.

### Test runtime-only

```text
/hexnametag test [gracz]
```

Ustawia testowy tag. Jeśli gracz nie jest podany, komenda działa na wykonującego. Z konsoli trzeba podać gracza.

Ten tryb nie zapisuje się do DB.

### Ustawienie/zastąpienie tagu gracza

```text
/hexnametag set <gracz> <Linia 1 | Linia 2 | Linia 3>
```

Przykład:

```text
/hexnametag set Radek <gold>HEX NETWORK | <aqua>Admin | <gray>Online
```

Efekt, od góry do dołu:

```text
HEX NETWORK
Admin
Online
```

`|` rozdziela linie. Komenda zawsze zastępuje cały aktualny tag gracza i zapisuje go w DB, jeśli `database.enabled=true` i `database.save-player-tags=true`.

### Ustawienie własnego tagu

```text
/hexnametag self <Linia 1 | Linia 2 | Linia 3>
```

Skrót dla gracza, który chce ustawić tag sobie.

### Usunięcie tagu

```text
/hexnametag clear [gracz]
```

Jeśli gracz nie jest podany, komenda działa na wykonującego. Z konsoli trzeba podać gracza.

Usuwa tag z pamięci, z aktualnego renderu i z DB, jeśli integracja HexCore DB jest aktywna.

### Podgląd aktualnych linii

```text
/hexnametag show <gracz>
```

Alias:

```text
/hexnametag info <gracz>
```

Pokazuje aktualne linie zapisane w pamięci pluginu dla online gracza.

### Reload

```text
/hexnametag reload
```

Przeładowuje config, restartuje task renderowania i wymusza odświeżenie aktualnych tagów.

## Pozycja napisu nad głową

Wysokość konfiguruje opcja:

```yml
style:
  bottom-offset-y: 0.2
```

Wartość oznacza położenie dolnej krawędzi całego napisu nad głową gracza. Przy wielu liniach plugin automatycznie podnosi środek `TextDisplay`, aby najniższa linia nadal zaczynała się `0.2` bloku/metra nad głową.

## Widoczność własnego tagu

W tej wersji domyślnie:

```yml
show-own-tag: true
```

Dzięki temu gracz widzi własny tag, np. podczas testu w F5. Jeśli na serwerze istnieje już stary `plugins/HexNameTags/config.yml`, Paper go nie nadpisze. Wtedy trzeba ręcznie zmienić:

```yml
show-own-tag: true
```

albo usunąć stary config i pozwolić pluginowi wygenerować nowy.

Jeśli `show-own-tag=false`, komenda nadal ustawi tag, ale wykonujący może go nie widzieć nad własną głową. Inni gracze w zasięgu nadal powinni go zobaczyć.

## API

Inne pluginy mogą użyć:

```java
HexNameTagsApi api = HexNameTagsProvider.get();
api.setPlayerTag(player, List.of(Component.text("Hex"), Component.text("Level 10")));
api.clearTag(player);
```

`setPlayerTag(...)` zapisuje tag do DB, jeśli `database.enabled=true` i `database.save-player-tags=true`.
Tagi zwykłych entity są runtime-only, bo UUID mobów/displayów zwykle nie jest stabilnym identyfikatorem gameplayowym po restarcie.

## Integracja z HexCore DB

Plugin pobiera `HexApi` przez Bukkit `ServicesManager`:

```java
HexApi hexApi = getServer().getServicesManager().load(HexApi.class);
```

Następnie używa:

```java
hexApi.db().db()
hexApi.db().async(...)
```

Tabela jest tworzona przez `Db.update(...)`, a finalna nazwa tabeli uwzględnia prefix HexCore:

```java
db.t("hex_nametags")
```

czyli przy prefixie np. `smp_` powstanie tabela `smp_hex_nametags`.

### Struktura tabeli

Domyślna tabela: `hex_nametags`, z prefixem z HexCore `Db.tablePrefix()`.

```sql
CREATE TABLE IF NOT EXISTS <prefix>hex_nametags (
  target_uuid VARCHAR(36) PRIMARY KEY,
  target_type VARCHAR(16) NOT NULL,
  lines_data TEXT NOT NULL,
  style_key VARCHAR(64) NOT NULL,
  enabled INTEGER NOT NULL,
  updated_at BIGINT NOT NULL
);
```

Kolumny:

- `target_uuid` — UUID gracza; klucz główny.
- `target_type` — obecnie `PLAYER`; zostawione pod przyszłe stabilne entity/custom targety.
- `lines_data` — linie tagu jako Base64-encoded MiniMessage, jedna fizyczna linia DB = jedna linia nametaga.
- `style_key` — obecnie `default`; przygotowane pod wiele stylów z configu.
- `enabled` — `1` aktywny wpis, `0` zarezerwowane pod ewentualne czasowe wyłączenie bez kasowania.
- `updated_at` — timestamp `System.currentTimeMillis()` ostatniej zmiany.

## Cache DB

Konfiguracja:

```yml
database:
  enabled: true
  create-table: true
  save-player-tags: true
  load-online-on-start: true
  table-name: hex_nametags
  cache-ttl-seconds: 10
```

Cache dotyczy odczytów tagu gracza z DB, np. przy join/reload. Domyślnie wynik jest ważny 10 sekund. `0` wyłącza cache odczytu. Zapis/clear aktualizuje lub czyści cache od razu.

## Ważne uwagi

1. To jest implementacja pod indeksy metadata display entities używane w 1.20.5-1.21.1. Jeśli aktualizujecie serwer na wyższe wersje, najpierw testujcie klasę `TextDisplayMetadata_1_21_1`.
2. `SetPassengers` nadpisuje listę pasażerów widoczną dla viewera. Plugin dopina obecnych realnych pasażerów targetu i na końcu fake tag, ale nietypowe pluginy od mountów mogą wymagać integracji.
3. Ukrywanie vanilla nametaga jest domyślnie wyłączone. Najbezpieczniej robić to osobnym pluginem od TAB/scoreboard albo dopisać integrację pod Wasz stack.
