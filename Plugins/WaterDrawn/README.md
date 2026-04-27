# WaterDrawn

`WaterDrawn` odpowiada tylko za mechanike tonięcia graczy.
To osobny plugin od podnoszenia poziomu wody (`WaterLevelFlood`).

## Co robi plugin
- sprawdza, czy gracz jest w strefie tonięcia,
- wysyła ostrzeżenia (actionbar + odliczanie),
- zabija gracza po czasie ustawionym w configu,
- pozwala przełączać tryb działania (`regions` albo `global`).

## Komendy (admin)
- `/waterdrawn reload` - przeładowuje `config.yml` bez restartu serwera.
- `/waterdrawn status` - pokazuje aktualny stan pluginu i kluczowe parametry.
- `/waterdrawn mode <global|regions>` - zmienia tryb działania:
  - `global` = tonięcie działa globalnie (wg ustawień świata),
  - `regions` = tonięcie działa tylko w regionach z configu.

## Dla kogo
- dla administratora eventu, który chce szybko sterować zasadami tonięcia,
- dla osoby konfigurującej mapę (regiony, wykluczenia, progi wysokości).

## Najważniejsze pliki
- `src/main/resources/config.yml` - pełna konfiguracja mechaniki,
- `src/main/resources/plugin.yml` - definicja komendy i uprawnień.
