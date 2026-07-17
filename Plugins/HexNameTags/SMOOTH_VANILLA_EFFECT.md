# Smooth vanilla-like nametags

Wersja 1.2.3 zmienia domyślny renderer z `packet-text-display-passenger` na `interpolated-follow`.

## Dlaczego poprzednia wersja nie działała dobrze?

- Cykliczne `SetPassengers` powodowało widoczny skok/remount napisu.
- Jednorazowy `SetPassengers` na Waszym Paper/Purpur 1.21.1 nie utrzymywał TextDisplay jako pasażera gracza, więc napis zostawał w miejscu.

## Aktualny mechanizm

Domyślnie plugin nie używa mounta/passengera.

Dla każdego viewera wysyłany jest packet-only `TextDisplay`. Plugin co kilka ticków wysyła do klienta nową pozycję tego displaya nad głową targetu, ale `TextDisplay` ma ustawione `teleportDuration`, więc klient interpoluje ruch płynnie zamiast pokazywać skok.

Czyli:

```text
spawn packet TextDisplay
metadata: text + style + teleport_duration
co 2 ticki: entity teleport packet do anchoru nad głową
klient: płynne przesunięcie przez teleport_duration
```

To nadal jest fake/packet entity, ale nie jest ArmorStandem, nie jest Bukkitową encją w świecie i nie jest mountem zmieniającym relację pasażerów gracza.

## Config

```yml
rendering:
  mode: interpolated-follow
  movement-update-interval-ticks: 2
  teleport-duration-ticks: 2
  min-move-distance: 0.02
```

Najważniejsze wartości:

- `movement-update-interval-ticks`: co ile ticków dosyłać pozycję displaya.
- `teleport-duration-ticks`: przez ile ticków klient ma interpolować ruch displaya do nowej pozycji.
- `min-move-distance`: filtr bardzo małych ruchów, żeby ograniczyć liczbę pakietów.

Najlepszy start testowy:

```yml
movement-update-interval-ticks: 2
teleport-duration-ticks: 2
min-move-distance: 0.0
```

Jeżeli napis jest zbyt opóźniony, daj:

```yml
movement-update-interval-ticks: 1
teleport-duration-ticks: 1
```

Jeżeli chcesz mniej pakietów kosztem lekkiego miękkiego doganiania:

```yml
movement-update-interval-ticks: 3
teleport-duration-ticks: 3
```

## Tryb awaryjny passenger

Można wrócić do starej ścieżki:

```yml
rendering:
  mode: packet-text-display-passenger
```

Nie jest to jednak domyślne, bo u Was jednorazowy passenger nie śledził gracza, a częste ponawianie `SetPassengers` powodowało skoki.
