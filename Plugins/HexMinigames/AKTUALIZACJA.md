# Aktualizacja balansu minigier

Podmień `HexMinigames-1.0.0.jar`, zaktualizuj poniższe wartości w folderze `plugins/HexMinigames/games` i uruchom ponownie serwer. Zachowaj własne współrzędne aren. Plugin nie nadpisuje istniejących plików YAML.

| Plik | Klucz | Wartość |
| --- | --- | --- |
| `popcorn.yml` | `round-time-seconds` | `45` |
| `popcorn.yml` | `settings.hazard.target-remaining-blocks` | `40` |
| `dalgona.yml` | `round-time-seconds` | `80` |
| `red_light_green_light.yml` | `settings.lights.red-grace-ticks` | `15` |
| `glass_bridge.yml` | `round-time-seconds` | `130` |
| `glass_bridge.yml` | `settings.scoring.finish-bonus` | `1` |
| `glass_bridge.yml` | `settings.respawn-delay-seconds` | `10` |

`red-grace-ticks` to czas tolerancji ruchu po przełączeniu światła na czerwone. 20 ticków to sekunda, więc 15 daje 0,75 s. Wartość jest konfigurowalna; np. 20 daje 1 s.

W `glass_bridge.yml` popraw też tekst tutorialu na „Dotarcie do mety daje dodatkowy +1 punkt” i dodaj informację o krótkich, losowych oknach PvP.

Przy ładowaniu dawnych domyślnych czasów plugin przelicza Popcorn 60/90 → 45 s, Dalgonę 60 → 80 s, most 150 → 130 s. Pozostałe czasy pozostają zgodne z konfiguracją.

Zmiany w mechanice działają po podmianie JAR-a:

- OP może używać wszystkich komend, ale nadal podlega regułom minigier; `/lobby` poprawnie wypisuje go z rundy.
- Popcorn po eliminacji przełącza gracza w spectator i teleportuje nad środek platformy. Tempo zanikania skaluje się do czasu rundy; pozostaje 40 bloków.
- Dalgona odpycha od granic stanowiska bez zatrzymywania grawitacji. Uderzenie w piasek niszczy go natychmiast w survivalu i nadal sprawdza wzór.
- Skucha na czerwonym odtwarza dźwięk niezadowolonego wieśniaka.
- Most kończy się po dotarciu wszystkich pozostałych uczestników do mety. Gracz oczekujący na respawn nadal liczy się jako uczestnik, który nie ukończył gry.
- Most losuje maksymalnie trzy oddzielne okna PvP po 3 s. Napisy `&6PVP &aON` i `&6PVP &cOFF` trwają 2 s; towarzyszy im krótki dźwięk bloku muzycznego. Walczą wyłącznie aktywni uczestnicy. Cios śmiertelny uruchamia zwykły respawn z opóźnieniem.
- HotHead: pierwsza faza ma trzy różne pary lamp i przerwę 16 ticków przy domyślnej konfiguracji (wcześniej 18). Druga zachowuje dwie pary, szybsze tempo oraz 2-sekundową pauzę przy komunikacie „Przyspieszamy!”.
