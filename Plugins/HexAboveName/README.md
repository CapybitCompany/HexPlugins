# HexAboveName

Lekki plugin Paper 1.21.x do tekstu nad domyślnym nickiem gracza.

## Założenia wydajnościowe

- Domyślnie używa `TextDisplay` jako passenger gracza.
- Nie teleportuje hologramu co tick.
- Nie dotyka LuckPerms, permissionów, scoreboard teamów, prefixów, tablisty ani nicku gracza.
- Widoczność sprawdzana jest domyślnie co 20 ticków, żeby napis znikał przy vanish/invisibility/spectator i respektował `viewer.canSee(player)`.

Przy 50-100 graczach i około 15 tytułach koszt jest niski: około `15 * liczba_online` prostych sprawdzeń na sekundę przy `visibility-refresh-ticks: 20`.

## Komendy

Permission: `hexabovename.admin`

```text
/hexabovename <nick> set <tytuł>
/hexabovename <nick> clear
/hexabovename reload
```

Alias:

```text
/han
```

## Przykłady

```text
/hexabovename Quezo set &6&lMISTRZ GRY
/hexabovename Quezo set <gold><bold>MISTRZ GRY</bold></gold>
/hexabovename Quezo clear
/hexabovename reload
```

## Build

Projekt jest pod Gradle i Java 21.

```bash
gradle build
```

Gotowy plik po zbudowaniu będzie w:

```text
build/libs/HexAboveName-1.0.0.jar
```

Jeżeli używasz IntelliJ IDEA: otwórz folder projektu jako Gradle project i uruchom task `build`.
