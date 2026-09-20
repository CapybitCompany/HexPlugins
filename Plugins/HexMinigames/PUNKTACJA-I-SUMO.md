# Punktacja serii, topka i Sumo

## Wgranie
Podmien Plugins/HexMinigames/build/libs/HexMinigames-1.0.0.jar w katalogu plugins serwera i uruchom serwer ponownie. Placeholdery wymagaja PlaceholderAPI. Nowe klucze messages.yml zostana dopisane automatycznie bez nadpisywania wlasnych tresci. Poprzednie domyslne minimum poczekalni (5) zmieni sie jednorazowo na 4. Kopie: config.yml.before-series-ui-1.bak i messages.yml.before-series-ui-1.bak.

## Seria
Produkcja HexEvents wymaga co najmniej 4 zapisanych graczy online PRZED zapisem stanu i teleportem do poczekalni. Domyslnie losuje 5 roznych minigier. W grze potrzeba minimum 2 uczestnikow (wyeliminowani obserwatorzy nadal uczestnicza w serii). Test administratora moze dzialac solo i nie zmienia historycznych punktow. Nie zmieniono zewnetrznej konfiguracji wydarzen HexEvents.

Po ostatniej rundzie uczestnicy wracaja do pregame.spawn, otrzymuja ranking calej serii (rowniez miejsca z zerem punktow), tytul zwyciezcy, jego nick w subtitle, fajerwerki bez obrazen i dzwiek minecraft:ui.toast.challenge_complete. Przy remisie w serii rozstrzyga nick alfabetycznie. Osoba, ktora opuscila serie, jest oznaczona i nie moze wygrac ani otrzymac punktow historycznych za te serie. Po przywroceniu ekwipunku uczestnicy pozostaja w lobby minigier.

Konfiguracja messages.yml:
- series-results, series-ranking-header, series-ranking-row
- W wierszu: {place}, {player}, {points}, {status}
- series-forfeited
- series-winner-title, series-winner-subtitle ({player})
- series-winner-sound, series-fireworks-enabled ('true' / 'false')

config.yml: games-per-series, pregame.minimum-players, series.minimum-continuation-players, durations.series-results-seconds. Produkcyjne minima nie moga zejsc ponizej 4/2.

## PlaceholderAPI
- %hexminigames_points% - twoje punkty historyczne.
- %hexminigames_top1% ... %hexminigames_top5% - nick i punkty.
- %hexminigames_top_1_name% ... %hexminigames_top_5_name% - sam nick.
- %hexminigames_top_1_points% ... %hexminigames_top_5_points% - same punkty.
Puste miejsce: '-' lub 0. Odczyty korzystaja z pamieci; zapis SQL i aktualizacja topki sa asynchroniczne. Baza przetrwa restart. Historyczne rekordy sprzed tej aktualizacji, ktore nie zawieraja nicku, pokazuja UUID do kolejnego ukonczenia serii przez gracza.

/hexminigames resetpunkty - zeruje punkty WSZYSTKICH graczy i topke (uprawnienie hexminigames.admin). Wymaga braku aktywnej sesji. Rejestr juz naliczonych serii pozostaje jako ochrona przed ponownym naliczeniem starych wynikow; nowe serie naliczaja sie od zera.

## Sumo
Poziome wybicie pozostaje 1.65, pionowe po trafieniu wzrasta z 0.28 do 0.52. Linie ochronne podczas zasad: X=431..441,Y=48,Z=370 oraz X=443..451,Y=50,Z=329. Znikaja przy starcie gry. Oryginalne bloki sa przywracane przy zakonczeniu lub anulowaniu.

## Elytra
Screen z rzeczywistym wyjatkiem ujawnil trzy bledne wspolrzedne w settings.ring-order:
- 76 -18 138 -> 76 -18 139 (poprawka odczytu screena w rewizji 7)
- 268 24 168 -> 268 24 108
- 245 7 170 -> 245 7 172

Poprawiono domyslna trase i migracje istniejacego games/elytra.yml do balance-revision: 7. Przy restarcie plugin poprawia tylko te trzy konkretne wpisy, zachowuje kolejnosc i inne ustawienia oraz zapisuje kopie elytra.yml.before-balance-7.bak. Nie trzeba usuwac konfiguracji ani zmieniac markerow na mapie. Walidacja wszystkich 21 obreczy pozostaje aktywna.
