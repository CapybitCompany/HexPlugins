# HexElimination

`HexElimination` obsługuje eliminację graczy w stylu eventowym.
Po śmierci gracz jest oznaczony jako wyeliminowany, a po respawnie trafia do `SPECTATOR`.

## Co robi plugin
- przy śmierci gracza:
  - oznacza go jako wyeliminowanego,
  - wywołuje efekt pioruna,
  - wysyła globalny komunikat o eliminacji,
- po respawnie/join:
  - pilnuje, aby wyeliminowany gracz był w trybie spectator,
- pozwala adminowi przywrócić gracza komendą.

## Komendy (admin)
- `/resurect <nick_gracza>` - wskrzesza wyeliminowanego gracza:
  - usuwa status eliminacji,
  - ustawia docelowy tryb gry (z configu),
  - wysyła globalny komunikat o wskrzeszeniu i kto je wykonał.
- `/resurectall [tryb_gry]` - wskrzesza **wszystkich** wyeliminowanych graczy naraz:
  - opcjonalnie przyjmuje tryb gry (`survival`, `creative`, `adventure`, `spectator`),
  - domyślnie używa trybu z configu (`settings.resurrect-gamemode`, domyślnie `SURVIVAL`),
  - czyści całą listę eliminacji (`eliminated.yml`),
  - wysyła globalny komunikat z liczbą wskrzeszonych graczy i wybranym trybem.

## Dla kogo
- dla administratora eventu PvP/survival,
- dla osoby pilnującej eliminacji i ręcznego przywracania graczy.

## Najważniejsze pliki
- `src/main/resources/config.yml` - ustawienia trybów gry i treści komunikatów,
- `eliminated.yml` - trwała lista wyeliminowanych graczy (UUID),
- `src/main/resources/plugin.yml` - komenda i uprawnienie admina.
