# HexNameTags changelog

## 1.2.3

- Zmieniono domyślny renderer na `interpolated-follow`.
- Usunięto zależność od cyklicznego `SetPassengers` w domyślnym trybie.
- Dodano płynne śledzenie pozycji przez `WrapperPlayServerEntityTeleport` + `Display.teleportDuration`.
- Dodano config:
  - `rendering.movement-update-interval-ticks`
  - `rendering.teleport-duration-ticks`
  - `rendering.min-move-distance`
- `packet-text-display-passenger` zostaje jako tryb awaryjny.

## 1.2.2

- Próba ograniczenia skoków przez wyłączenie cyklicznego remounta.

## 1.2.0

- Komendy administracyjne do ustawiania i edycji tagów graczy.
- Integracja z HexCore DB.
