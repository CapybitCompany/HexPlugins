# StatsApi

`StatsApi` to osobny backend HTTP (Spring Boot) do bezpiecznego udostepniania statystyk
z bazy danych dla strony WWW.

## Co robi
- udostepnia rankingi i statystyki graczy przez REST API,
- mapuje statystyki z pliku `stats.yml` (bez hardkodowania endpointow),
- zwraca dane w formacie: `uuid`, `nickname`, `value`,
- nie pozwala frontendowi laczyc sie bezposrednio z SQL.

## Endpoints
- `GET /api/stats` - lista dostepnych statystyk.
- `GET /api/stats/{statId}?limit=10` - ranking dla statystyki.
- `GET /api/stats/{statId}/player/{uuid}` - statystyka jednego gracza.

## Konfiguracja
- `src/main/resources/application.yml` - port, datasource, import pliku stats.
- `src/main/resources/stats.yml` - rejestr statystyk (dodajesz nowe bez zmian kodu).

## Uruchomienie lokalne
```powershell
cd C:\workspace\HexPlugins\HexPlugins
.\gradlew.bat :statsapi:bootRun
```

## Build
```powershell
cd C:\workspace\HexPlugins\HexPlugins
.\gradlew.bat :statsapi:build
```

