# Poprawki balansu

Podmień HexMinigames-1.0.0.jar i zrestartuj serwer. Nie używaj /reload.
Przy pierwszym uruchomieniu ta wersja aktualizuje dawne domyślne wartości
w games/jump_rope.yml oraz games/breeze_tower.yml. Inne wartości zostają zachowane.
Przed zapisem powstają kopie *.before-balance-1.bak, a balance-revision: 1
zapobiega ponownej migracji po późniejszych ręcznych zmianach administratora.

- Skakanka: całe U o 1 blok niżej (dół Y=12, góra Y=17), pełny obrót 80 ticków,
  czyli 4 sekundy. Kolizja ma 0,05 bloku tolerancji na krawędziach szkła, żeby
  prawidłowo wykonany zwykły skok mógł ominąć obracające się narożniki.
- Dalgona: wyłączone natychmiastowe niszczenie; piasek niszczy się naturalnie w survivalu.
- Szklany most: actionbar kończy się na metrach i „| PVP ON/OFF”.
  PvP trwa 60 ticków (3 s), przerwy trwają losowo 300–600 ticków (15–30 s).
  Nie ma limitu liczby aktywacji; okna mieszczą się w czasie rundy.
  Zmiany sygnalizują dźwięki note_block.bell oraz note_block.chime, bez subtitle.
  Dotknięcie złego szkła od razu zalicza upadek i rozpoczyna respawn z delayem:
  nie można odbić się z niego przez trzymanie spacji. Sprawdzane są również
  krawędzie pod stopami i przecięcie wysokości platformy podczas opadania.
- Popcorn: po 30 tickach (1,5 s) bez przesunięcia poziomego o co najmniej 0,2 bloku
  ostrzeżenie i odliczanie 2, 1. Po 70 tickach (3,5 s) bezruchu eksplozja,
  spectator i teleport nad platformę. Przemieszczenie kasuje odliczanie;
  obracanie kamery i skakanie w miejscu nie kasują go.
- Czerwone zielone: przedmioty nazywają się kolorowo CZERWONE / ZIELONE.
  Po upływie limitu czasu gracze bez mety otrzymują efekt eksplozji.
- Wieża Breeze'a: interwał 12 ticków (0,6 s), 20% szansy na równoczesny
  dodatkowy strzał z innego NPC. Gdy brak widocznego aktywnego gracza,
  NPC celuje w losową powierzchnię platformy między Breeze'ami.
  Chunks areny są ładowane przed stworzeniem NPC i utrzymywane przez całą rundę,
  a następnie zwalniane. Błędy przygotowania/startu/ticków zapisują pełny stos
  wywołań w konsoli. Bez logu serwera nie potwierdzono przyczyny zgłoszonego
  natychmiastowego zakończenia tej areny.

Tempo skakanki: settings.rope.rotation-ticks.
Tempo podmuchów: settings.shots.interval-ticks.
Test skoku obejmuje pełną trajektorię i kolizję pomiędzy tickami przy stopach na Y=12.
Odczucie tempa, opóźnienia sieciowe i układ rzeczywistej areny wymagają próby na serwerze.
