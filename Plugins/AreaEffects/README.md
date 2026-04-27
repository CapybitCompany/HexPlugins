# AreaEffects

`AreaEffects` to osobny plugin do efektów wizualnych i dźwiękowych.
Służy do odtwarzania sceny kolizji (cząsteczki, wybuchy wizualne, dźwięki)
w wybranym obszarze mapy.

## Co robi plugin
- uruchamia sekwencję efektów na określony czas,
- losuje punkty efektów wewnątrz zadanego obszaru,
- odtwarza particle, dźwięki i opcjonalne wybuchy bez niszczenia mapy.

## Komendy (admin)
- `/areaeffects start` - uruchamia sekwencję efektów.
- `/areaeffects stop` - natychmiast zatrzymuje sekwencję.
- `/areaeffects status` - pokazuje, czy efekty aktualnie działają.
- `/areaeffects reload` - przeładowuje `config.yml`.

## Dla kogo
- dla administratora eventu, który ręcznie odpala scenę zderzenia,
- dla osoby konfigurującej intensywność i obszar efektów.

## Najważniejsze pliki
- `src/main/resources/config.yml` - obszar, czas, typy efektów i głośność,
- `src/main/resources/plugin.yml` - komendy i uprawnienia.
