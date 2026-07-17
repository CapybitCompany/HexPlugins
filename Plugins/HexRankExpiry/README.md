# HexRankExpiry

`HexRankExpiry` sprawdza czasowe rangi zapisane przez LuckyPerms w tabeli `luckperms_user_permissions` i informuje gracza przy wejściu na serwer, ile dni zostało do końca jego rangi.

Domyślnie plugin monitoruje uprawnienia/rangi:

- `nte.elita`
- `nte.svip`
- `nte.vip`

Liczone są wyłącznie wpisy, które mają:

- `uuid` zgodny z UUID gracza,
- `permission` zgodne z jedną z rang w `config.yml`,
- `value = 1`,
- `expiry` większe od aktualnego czasu unix timestamp.

Wpisy permanentne LuckyPerms (`expiry = 0`) nie są traktowane jako rangi czasowe i nie wywołują wiadomości.

## Wymagania

- Purpur/Paper API `1.21.11` (Java 21)
- `HexCore` — plugin korzysta z połączenia DB wystawianego przez `HexCore`
- LuckyPerms z tabelą SQL `luckperms_user_permissions`
- Opcjonalnie `PlaceholderAPI`, jeśli chcesz używać placeholderów w innych pluginach

Inne pluginy **nie muszą deklarować `depend` ani `softdepend` na `HexRankExpiry`**. Do integracji używają normalnych stringów PlaceholderAPI, np. `%hexrankexpiry_days%`.

## Instalacja

1. Zbuduj JAR modułu:

```powershell
.\gradlew.bat :plugins:HexRankExpiry:build
```

2. Skopiuj plik:

```text
Plugins/HexRankExpiry/build/libs/HexRankExpiry-1.0.0.jar
```

do katalogu `plugins/` serwera.

3. Upewnij się, że `HexCore` ma skonfigurowane połączenie z tą samą bazą danych, w której znajduje się tabela LuckyPerms.

## Konfiguracja

Domyślna konfiguracja znajduje się w `config.yml`:

```yaml
luckyperms:
  user-permissions-table: "luckperms_user_permissions"

ranks:
  - permission: "nte.elita"
    display: "&dELITA"
  - permission: "nte.svip"
    display: "&bSVIP"
  - permission: "nte.vip"
    display: "&6VIP"
```

Kolejność w sekcji `ranks` oznacza priorytet wyświetlania. Jeśli gracz ma kilka aktywnych rang czasowych naraz, plugin pokaże pierwszą pasującą rangę z listy.

> Uwaga: standardowa nazwa tabeli LuckyPerms to `luckperms_user_permissions` — bez litery `y` po `luck`. Plugin automatycznie próbuje też fallback `luckyperms_user_permissions`, jeśli używasz niestandardowej nazwy.

## Wiadomość przy wejściu

Po wejściu gracza plugin asynchronicznie sprawdza DB i wysyła wiadomość tylko wtedy, gdy gracz ma aktywną czasową rangę z konfiguracji.

Dostępne tokeny w wiadomościach:

- `{rank}` — nazwa wyświetlana rangi, np. `VIP`
- `{permission}` — techniczna nazwa uprawnienia, np. `nte.vip`
- `{days}` — liczba dni do końca rangi, zaokrąglona w górę
- `{day_word}` — `dzień` albo `dni`
- `{seconds}` — liczba sekund do wygaśnięcia
- `{expiry}` — unix timestamp z kolumny `expiry`

## PlaceholderAPI

Identyfikator ekspansji:

```text
hexrankexpiry
```

Placeholdery:

| Placeholder | Opis |
| --- | --- |
| `%hexrankexpiry_days%` | Liczba dni do końca rangi. Brak/wygaśnięta ranga: `0`. |
| `%hexrankexpiry_days_text%` | Tekst typu `7 dni` albo `1 dzień`. |
| `%hexrankexpiry_seconds%` | Liczba sekund do końca rangi. |
| `%hexrankexpiry_rank%` | Nazwa wyświetlana aktywnej rangi. |
| `%hexrankexpiry_permission%` | Techniczna nazwa uprawnienia/rangi, np. `nte.vip`. |
| `%hexrankexpiry_expiry%` | Unix timestamp wygaśnięcia. |
| `%hexrankexpiry_has_rank%` | `true` / `false`. |
| `%hexrankexpiry_message%` | Jednowierszowy, kolorowany tekst z `config.yml`. |

Przykład użycia w DeluxeMenus lub innym pluginie obsługującym PlaceholderAPI:

```yaml
lore:
  - '&7Twoja ranga: %hexrankexpiry_rank%'
  - '&7Wygasa za: &a%hexrankexpiry_days_text%'
```

## Komendy

```text
/hexrankexpiry
/hexrankexpiry reload
/hexrankexpiry refresh <nick>
```

Uprawnienie administracyjne:

```text
hexrankexpiry.admin
```

## Uwagi techniczne

- Odczyty DB są wykonywane asynchronicznie przez usługę DB z `HexCore`.
- Placeholdery korzystają z cache, aby nie wykonywać zapytań SQL synchronicznie podczas parsowania placeholderów.
- Domyślny TTL cache to `60` sekund.
- Dni są liczone przez zaokrąglenie w górę: jeśli do końca zostały 2 godziny, gracz zobaczy `1 dzień`.
