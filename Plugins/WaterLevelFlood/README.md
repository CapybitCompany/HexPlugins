# WaterLevelFlood

`WaterLevelFlood` odpowiada za podnoszenie poziomu wody w wybranym obszarze mapy.
To plugin sterujący samym zalewaniem (bez mechaniki tonięcia, ktora jest w `WaterDrawn`).

## Co robi plugin
- zalewa skonfigurowany obszar warstwa po warstwie,
- podnosi poziom wody w czasie ustawionym w configu,
- pozwala zatrzymać i zresetować zalanie.

## Komendy (admin)
- `/flood start` - uruchamia zalewanie od poziomu startowego.
- `/flood stop` - zatrzymuje podnoszenie poziomu wody.
- `/flood reset` - usuwa wodę postawioną przez event i przywraca stan początkowy.
- `/flood status` - pokazuje aktualny stan eventu (poziom i cel).
- `/flood reload` - przeładowuje `config.yml`.

## Dla kogo
- dla administratora eventu, który steruje przebiegiem zalewania,
- dla osoby konfigurującej obszar i tempo wzrostu poziomu wody.

## Najważniejsze pliki
- `src/main/resources/config.yml` - region, poziomy Y, tempo i komunikaty,
- `src/main/resources/plugin.yml` - komendy i uprawnienia.

