# HexElimination

`HexElimination` obsługuje eliminację graczy w stylu eventowym.
Po śmierci gracz jest oznaczony jako wyeliminowany, a po respawnie trafia do `SPECTATOR` w miejscu, w którym zginął.

## Co robi plugin
- przy śmierci gracza:
  - oznacza go jako wyeliminowanego,
  - zapamiętuje miejsce śmierci jako miejsce respawnu,
  - wywołuje efekt pioruna,
  - wysyła globalny komunikat o eliminacji,
  - domyślnie pomija konta `OP` (można to zmienić flagą w configu),
- po respawnie/join:
  - pilnuje, aby wyeliminowany gracz był w trybie spectator,
- pozwala adminowi przywrócić gracza komendą.

## Komendy (admin)
Wszystkie komendy działają przez jeden wpis `/hexelimination` (alias: `/helim`).

- `/hexelimination start` — rozpoczyna okres eliminacji.
- `/hexelimination stop` — kończy okres eliminacji.
- `/hexelimination resurect <nick>` — wskrzesza wyeliminowanego gracza:
  - usuwa status eliminacji,
  - ustawia docelowy tryb gry (z configu),
  - wysyła globalny komunikat o wskrzeszeniu.
- `/hexelimination resurectall [tryb_gry]` — wskrzesza **wszystkich** wyeliminowanych:
  - opcjonalnie przyjmuje tryb gry (`survival`, `creative`, `adventure`, `spectator`),
  - domyślnie używa trybu z configu (`settings.resurrect-gamemode`, domyślnie `SURVIVAL`),
  - czyści całą listę eliminacji,
  - wysyła globalny komunikat.
- `/hexelimination reload` — przeładowuje konfigurację pluginu.

## Ustawienia
- `settings.active-on-startup` (domyślnie: `true`):
  - `true` - eliminacje działają od razu po włączeniu pluginu,
  - `false` - eliminacje ruszają dopiero po `/hexelimination start`,
- `settings.respawn-gamemode-for-eliminated` (domyślnie: `SPECTATOR`):
  - tryb gry ustawiany po respawnie wyeliminowanego gracza,
- `settings.include-ops-in-elimination` (domyślnie: `false`):
  - `false` - gracze `OP` nie są eliminowani (bezpieczne na produkcji),
  - `true` - eliminacja działa też na `OP` (przydatne do testów).

## Komunikaty UI
- Teksty wiadomości są obsługiwane przez `HexCore` w `plugins/HexCore/ui.yml`.
- Plugin używa kluczy `elimination.*`, np. `elimination.reload.ok`, `elimination.kill.announce`.
- Domyślne wpisy są też rejestrowane runtime przez HexElimination, ale `ui.yml` jest bezpiecznym fallbackiem.
- Własne treści najlepiej ustawiać w `overrides` w `ui.yml`.

## Dla kogo
- dla administratora eventu PvP/survival,
- dla osoby pilnującej eliminacji i ręcznego przywracania graczy.

## Najważniejsze pliki
- `src/main/resources/config.yml` - ustawienia trybów gry i zachowania eliminacji,
- `plugins/HexCore/ui.yml` - templateki i nadpisania wiadomości UI (`elimination.*`),
- `eliminated.yml` - trwała lista wyeliminowanych graczy (UUID),
- `src/main/resources/plugin.yml` - komenda i uprawnienie admina.
