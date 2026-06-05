# HexAboveName 1.1.0

Poprawiona wersja: **bez passengerów**.

Plugin NIE robi:
- `player.addPassenger(...)`,
- zmian LuckPerms,
- zmian permisji,
- zmian scoreboard/team,
- zmian nicku/tablisty/prefixu.

Dzięki temu nie powinien psuć WorldGuarda, regionów VIP ani skryptów traktujących gracza jako normalnego gracza.

## Komendy

```txt
/hexabovename <nick> set <tytuł>
/hexabovename <nick> clear
/hexabovename reload
/han <nick> set <tytuł>
```

Permission:

```txt
hexabovename.admin
```

## Budowanie

```bash
gradle build
```

Gotowy plik:

```txt
build/libs/HexAboveName-1.1.0.jar
```

## Wydajność

Domyślnie aktualizuje pozycję co 2 ticki tylko dla graczy, którzy mają tytuł.
Przykład: 15 tytułów = około 150 lekkich teleportów TextDisplay na sekundę.
To jest normalnie akceptowalne przy 50-100 graczach, a nie dotyka stanu gracza.

Jeśli chcesz mniej obciążenia, ustaw:

```yml
settings:
  update-interval-ticks: 3
  teleport-duration-ticks: 3
```

