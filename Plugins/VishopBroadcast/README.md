# VishopBroadcast

Plugin Paper/Spigot do zapisywania zakupów z vishop w bazie HexCore i emitowania skonfigurowanych komunikatów na każdym serwerze, na którym działa plugin.

## Komenda

```text
/vishopbroadcast <nick> <usluga> [liczba] [kwota]
```

Przykłady:

```text
/vishopbroadcast HaViX Elita - 49.99
/vishopbroadcast HaViX Vip - 19.99
/vishopbroadcast HaViX Coins 1000 19.99
/vishopbroadcast HaViX Dar 50
```

Plugin nie wymaga ID zakupu z ViShop. Jeżeli ViShop wykona tę samą komendę na kilku serwerach naraz, plugin użyje automatycznej deduplikacji po zestawie: gracz + usługa + liczba + kwota. Domyślne okno deduplikacji to 10 sekund (`settings.dedupe.window-seconds`). Dzięki temu zakup za `19.99` nie zmieni się w `99.95` przy pięciu serwerach.

Jeżeli usługa nie ma ilości, ale chcesz przekazać kwotę, użyj `-` jako pustej ilości, np.:

```text
/vishopbroadcast {nick} Vip - 19.99
/vishopbroadcast {nick} SVIP - 29.99
/vishopbroadcast {nick} Elita - 49.99
```

Jeżeli kiedyś używana integracja ViShop udostępni stabilny identyfikator transakcji, można nadal podać go jako ostatni argument:

```text
/vishopbroadcast {nick} Vip - 19.99 {transaction_id}
```

Wtedy duplikaty są rozpoznawane dokładnie po tym ID. Bez ID działa deduplikacja czasowa.

## Najważniejsze funkcje

- konfigurowalne usługi (`Vip`, `SVIP`, `Elita`, `Coins`, `Dar` i kolejne dodane w `config.yml`),
- domyślnie wszystkie komunikaty zakupów wyświetlają się tylko na czacie (`CHAT`),
- kolejka RAM, która nie pomija zakupów nawet przy wielu logach w jednym odpytywaniu,
- automatyczna deduplikacja bez ID zakupu, zabezpieczająca przed wielokrotnym naliczeniem tej samej komendy z kilku serwerów,
- odpytywanie tabeli logów domyślnie co 15 sekund na każdym serwerze niezależnie,
- minimalny odstęp 1 sekundy dla komunikatów wyłącznie czatowych,
- nocne czyszczenie logów starszych niż skonfigurowana liczba dni,
- tabela sum wydatków gracza oraz tabela logów zakupów.

## Tabele

Domyślne nazwy tabel:

- `vishop_player_totals` — `uuid`, `player_name`, `total_spent`, `updated_at`,
- `vishop_purchase_logs` — opcjonalne `external_id`, data zakupu, usługa, gracz, ilość, kwota, treść logu, wykonawca,
- `vishop_purchase_dedupe` — techniczna tabela krótkich blokad deduplikacji, czyszczona automatycznie.

Nazwy tabel można zmienić w `config.yml` w sekcji `tables`.

## Placeholdery w wiadomościach

Dostępne placeholdery:

- `{player}` — nick kupującego,
- `{uuid}` — UUID kupującego,
- `{service}` — sformatowana nazwa usługi z konfiguracji,
- `{service_raw}` — klucz usługi z konfiguracji,
- `{amount}` — liczba/ilość,
- `{price}` — kwota,
- `{amount_part}` — opcjonalny, gotowy fragment z ilością,
- `{price_part}` — opcjonalny, gotowy fragment z ceną,
- `{info}` — informacja zapisana w logu,
- `{date}` — data zakupu,
- `{server}` — nazwa serwera Bukkit.

Wiadomości obsługują MiniMessage, np. `<gold>VIP</gold>`, oraz proste legacy kolory `&`, jeśli tekst nie zawiera tagów MiniMessage.

## Build

Z katalogu głównego repozytorium:

```powershell
.\gradlew.bat :plugins:VishopBroadcast:build
```

Wynikowy plik JAR znajdziesz w:

```text
Plugins/VishopBroadcast/build/libs/VishopBroadcast-1.0.0.jar
```

Plugin wymaga działającego `HexCore` z poprawnie skonfigurowaną bazą danych.

