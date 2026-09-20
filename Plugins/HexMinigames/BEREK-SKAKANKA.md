# Berek i Skakanka

Po podmianie JAR-a podmień dotychczasowe puste pliki `plugins/HexMinigames/games/tag.yml` i `jump_rope.yml` na wersje z projektu, a następnie zrestartuj serwer. Istniejące YAML-e nie są automatycznie nadpisywane.

## Berek — tag

Region: `327 -34 -90` do `378 -8 -141`. Spawn: `357 -30 -118`. Zasady: 20 s. Gra: 150 s.

Losowane role:

| Uczestnicy | Berkowie |
| --- | --- |
| 4 | 1 |
| 5–8 | 2 |
| 9–14 | 3 |

Do testów mechanika obsługuje też 2–3 graczy z jednym berkiem; normalna konfiguracja wymaga minimum 4.

Berkowie mają czerwony obrys, uciekający zielony. Wyłącznie bezpośrednie uderzenie ręką przez berka przekazuje rolę. Strzały, obrażenia zdrowia i upadku są blokowane. Po przekazaniu roli dawny berek ma 40 ticków (2 s) ochrony przed ponownym otrzymaniem berka. Nowy berek słyszy negatywny dźwięk wieśniaka i widzi czerwone „Berek!” przez 1 s bez przejść. Actionbar zawiera czas i aktualną rolę.

Proponowana punktacja jest już ustawiona: łączny czas w roli uciekającego daje 1 punkt od 30 s, 2 od 75 s, 3 od 120 s. Progi nie sumują się; zmienia się je w `settings.scoring.runner-seconds`. Długość ochrony ustawia `settings.immunity-ticks`.

Po wyjściu uczestnika liczba berków jest dostosowywana; gdy nie ma już dwóch osób do gry, runda się kończy. Po rundzie wracają poprzednie scoreboardy i stan obrysu.

## Skakanka — jump_rope

Region: `414 -33 -94` do `486 33 -45`. Spawn: `450 12 -52`, północ. Zasady: 20 s. Gra: 90 s.

Linia startu Z = −56 odpycha podczas zasad i respawnu, zachowując pionowy ruch gracza. Meta Z = −83, w granicach X = 446–454, wymaga przekroczenia od strony startu; skok nad linią też się liczy. Przejście daje 2 punkty, brak przejścia 0. Gdy wszyscy dotrą do mety, gra się kończy. PvP jest wyłączone.

Przy Y ≤ −23 gracz wybucha i wraca na spawn. Domyślny delay wynosi 10 s i zaczyna się dopiero po zakończeniu teleportu. Nieudana próba nie blokuje kolejnych podejść.

### Przyjęta geometria ze screenów

36 bloków czarnego szkła tworzy U:

- X = 450;
- dolny odcinek: Y = 13, Z od −82 do −57;
- końce na Z = −82 i −57 sięgają Y = 18;
- oś obrotu biegnie wzdłuż Z przez środki górnych bloków, czyli X = 450,5 i Y = 18,5.

Ruch jest przedstawiony przez BlockDisplay z interpolacją. Ponieważ [encje wyświetlające służą do wizualizacji](https://docs.papermc.io/paper/dev/display-entities/), kontakt i odepchnięcie obsługuje własna geometria kolizji, sprawdzająca także pozycje pomiędzy tickami. Nie są to przesuwane, pełne bloki świata, na których można stać.

Oryginalne czarne szkło w podanych pozycjach jest tymczasowo ukrywane i przywracane po zakończeniu/przerwaniu gry. Inne materiały nie są zastępowane.

| Klucz w settings | Domyślnie | Znaczenie |
| --- | --- | --- |
| `rope.rotation-ticks` | 100 | Pełny obrót w 5 s |
| `rope.knockback` | 0.9 | Siła odepchnięcia poziomego |
| `rope.hit-cooldown-ticks` | 10 | Minimum 0,5 s między odepchnięciami tej samej osoby |
| `respawn-delay-seconds` | 10 | Opóźnienie kolejnej próby |
| `rope.x / pivot-y / bottom-y / near-z / far-z` | jak wyżej | Położenie i rozmiar skakanki |

Testy automatyczne obejmują kolizję, skok, kierunek przekroczenia mety, respawn z opóźnieniem i przywracanie szkła. Oś obrotu została wywnioskowana ze screenów; jej zgodność z zamysłem oraz odczuwalne tempo i siła wymagają próby na arenie.

## Komendy testowe

- `/hexminigames testareny tag Nick1 Nick2 Nick3 Nick4`
- `/hexminigames testareny jump_rope Nick`
