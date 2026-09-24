# Serie produkcyjne

- Co sekundę plugin sprawdza faktyczną obecność graczy w `pregame.region` świata `Hex_Minigames`. Minimum to 4 (`pregame.minimum-players` może zwiększyć próg). Uruchamia standardowe losowanie i serię pięciu gier.
- Przed teleportacją do świata minigierek zapisuje ekwipunek i pozycję źródłową w `snapshots.db`. Seria przejmuje ten zapis, zamiast zapisywać pozycję w poczekalni. Dotyczy także wejść teleportowanych przez HexEvents.
- Po pięciu grach zwycięzca otrzymuje z konsoli `dajpunkt global <nick> 1`. Przy remisie każdy lider dostaje jedną komendę, a tytuł pokazuje `&6Remis!` i nicki. Dotyczy produkcji i `/test`; pojedyncze `/testareny` nie przyznaje nagrody za serię. Komenda ma zabezpieczenie przed ponowieniem; brak pluginu obsługującego komendę nie tworzy kolejki zaległych nagród.
- Po `durations.series-results-seconds` (domyślnie 12 s) następuje powrót do zapisanej pozycji i przywrócenie ekwipunku. Nieudana próba jest ponawiana co sekundę, a zapis pozostaje do udanego przywrócenia. Gracz offline odzyskuje stan po wejściu.
- Przerwa między grami wynosi 7 s: wyniki 2 s, przerwa 0 s, odliczanie następnej gry 5 s. Tutorial pierwszej gry zachowuje swój czas; następne korzystają z `countdowns.round`.
- Popcorn trwa 35 s, zmienia etap co 8 ticków, zostawia 12 bloków.
- Sumo pozwala walczyć bez blokady lotu po wybiciu z płytki. Bomby spadają nad areną, są automatycznie podnoszone i rzucane PPM. Eksplozja odpycha także rzucającego; nie niszczy bloków, nie podpala i nie zabiera zdrowia.
- `games/monkey_run.yml`, sekcja `settings.bombs`: `enabled`, `min-interval-seconds` (8), `max-interval-seconds` (16), `spawn-height` (6 ponad wysokością głowy), `radius` (7), `knockback` (1.8). Pojawieniu towarzyszy `entity.glow_squid.ambient`.
- Zielone obręcze Elytry odświeżają się bez limitu 96 bloków, także po ponownym wczytaniu chunka i ukończeniu trasy.

Przy pierwszym uruchomieniu aktualizacji istniejące ustawienia przerw i Popcornu są migrowane, a brakujące opcje bomb i remisu uzupełniane. Oryginały pozostają w plikach `*.before-live-series-1.bak`. Późniejsze zmiany administratora nie są nadpisywane.

Aktualizację należy wgrać przy zatrzymanym serwerze. Gdy gracz był już w świecie minigierek przed uruchomieniem tej wersji i nie ma jego zapisu, plugin nie potrafi odtworzyć nieznanej poprzedniej pozycji: gracz musi wrócić na `world` i wejść ponownie. Seria nie kasuje jego ekwipunku bez poprawnego zapisu powrotu.

Do sprawdzenia na serwerze: start z 3/4 graczami, pełne pięć gier z remisem, powrót i rozłączenie, ponowne wczytywanie odległych obręczy, podnoszenie/rzucanie bomb oraz odrzut przy powrocie z płytki Sumo.

## Aktualizacja 1.0.2

Kolory Berka obsługuje zainstalowany osobno GlowAPI (sprawdzona składnia wersji 2.0.1): konsola wykonuje `glowapi:glow red <nick>` dla berka i `glowapi:glow green <nick>` dla uciekających. Po przekazaniu berka oba kolory zmieniają się natychmiast; wyjście i koniec rundy wykonują `glowapi:glow off <nick>`. Powtórne sprzątanie nie powiela komend. Nowi obserwatorzy dostają odświeżenie także po 10 tickach, aby uwzględnić zakończenie logowania. HexMinigames nie tworzy własnych drużyn ani filtrów pakietów. Brak GlowAPI jest zgłaszany przed tutorialem Berka.

Dokumentacja integracji: https://modrinth.com/plugin/glowapi — sekcja Commands. GlowAPI sam obsługuje pakiety koloru; inne pluginy nadpisujące drużyny klienta nadal mogą wpływać na efekt (ograniczenie opisane przez autora GlowAPI).
Odrzut przy przekazaniu berka: poziomo 1.25, pionowo 0.42. Sumo obsługuje cios przez PrePlayerAttackEntityEvent, zanim nietykalność po teleportacji może zablokować zdarzenie obrażeń. Właściwe zdarzenie obrażeń nie nalicza drugiego odrzutu. Bomba: poziomo 1.8, pionowo 0.55. Stare domyślne 2.5 jest jednorazowo migrowane w istniejącym configu, z kopią *.before-bomb-knockback-1.bak.

JAR ma wersję 1.0.2. Przy zatrzymanym serwerze podmień poprzedni JAR HexMinigames i pozostaw GlowAPI w katalogu plugins. Usuń GlowPlayers, jeśli nadal jest zainstalowany, ponieważ automatycznie nadaje własne kolory. Uruchom serwer ponownie i sprawdź `/version HexMinigames` oraz `/glowapi version`.

Test Berka dla dwóch graczy: `/hexminigames testareny tag Nick1 Nick2`. Sprawdź czerwony kolor szukającego, zielony uciekającego, zamianę po uderzeniu, widok obserwatora oraz usunięcie kolorów po rundzie. Testy automatyczne sprawdzają komendy i cykl rundy; wygląd wymaga sprawdzenia w klientach Minecraft na docelowym serwerze.