# Disco Floor i poprawki

Podmień JAR i zrestartuj serwer. Pusty, dotychczas nieużywany disco_floor.yml
zostanie automatycznie uzupełniony. Stara domyślna prędkość skakanki 80
zostanie zmieniona na 60 ticków (3 sekundy). Przed migracją powstają
kopie *.before-balance-2.bak. Własna geometria skakanki z poprzedniej migracji
pozostaje zachowana.

- Szklany Most: 0–19 m = 0 pkt, 20–39 m = 1 pkt, 40–59 m = 2 pkt,
  co najmniej 60 m = 3 pkt; meta dodatkowo +1. Najlepszy dystans zostaje
  zachowany po upadku. Dotknięcie złego szkła od razu oznacza karę,
  także przy wybiciu ze spacji, na krawędzi lub przy przecięciu powierzchni w ruchu.
- Po rundzie ruch nie uruchamia ponownie zresetowanej bariery startowej.
  Respawny oczekujące w kolejce są anulowane, gracze zostają w miejscu
  na czas wyników (także jeśli byli akurat w powietrzu).
  Zakończenie całej sesji nadal przywraca stan sprzed minigier.
- Popcorn: ostrzeżenie po 1,5 s bezruchu, 2 s na reakcję, eliminacja po 3,5 s.
- HotHead: ogień i obrażenia od podpalenia przez 2 s od kontaktu;
  duszki ograniczone do regionu -27 -32 37 / -10 -29 54.
- Breeze: ładowanie i utrzymywanie chunków przed stworzeniem NPC;
  ochrona przed anulowaniem spawnu dotyczy wyłącznie NPC tworzonych
  przez tę rundę. Nie odblokowuje zwykłego spawnu mobów.
  Bez logu produkcyjnego nie ustalono jednoznacznie przyczyny poprzedniej awarii.

## Disco Floor

Komenda: /hexminigames testareny disco_floor Nick

Region: 549 -37 -30 / 590 -4 11. Spawn: 570 -31 -10.
Platforma: 552 -32 -27 / 587 -32 8. Odczytywany jest istniejący wzór mapy
z dziewięciu wskazanych kolorów betonu. Losowanie wybiera tylko kolory faktycznie
obecne na platformie; przy co najmniej dwóch kolorach nie powtarza poprzedniego.
Gdy na podanych współrzędnych nie ma żadnego właściwego betonu, przygotowanie
kończy się błędem opisującym brak platformy.

Po 20 s zasad jest 7 rund po 11 s:
3 s zapowiedzi koloru w actionbarze, ujawnienie koloru i 3 s na reakcję
(subtitle bez zanikania + dźwięk), następnie 5 s bez pozostałych kolorów.
Platforma wraca przed następną rundą oraz podczas resetu/anulowania gry.
PvP i obrażenia są wyłączone; Y <= -47 eliminuje z eksplozją,
spectatorem i teleportem na 577 -26 -3.

Punktowane są ukończone rundy: 0–1 = 0 pkt, 2 = 1 pkt, 3–4 = 2 pkt,
5–6 = 3 pkt, 7 = 4 pkt. Eliminacja zamraża wynik.
