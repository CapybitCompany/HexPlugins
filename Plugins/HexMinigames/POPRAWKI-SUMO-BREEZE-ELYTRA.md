# Poprawki Sumo, Breeze i Elytry

- Sumo: pojedynczy impuls w stron? wyspy, bez sterowania lotem i teleportu przy l?dowaniu. Niski ?uk (oko?o 2,5 bloku ponad start), oko?o sekundy lotu; rzeczywisty punkt l?dowania zale?y od ruchu gracza i kolizji.
- Sumo: czas na wyspie w formacie sekundy:setne (00:00), od?wie?any co tick. Przy 20 TPS setne rosn? co 05. Progi punkt?w pozostaj? 20/40/60 sekund.
- Sumo: poziomy knockback 1,65 zamiast 1,1, pionowy 0,28 zamiast 0,4, odst?p trafie? 5 zamiast 10 tick?w.
- Breeze: strza?y co 6 tick?w, pierwszy po 20 tickach, pr?dko?? 1,35, mno?nik podmuchu 2,6, minimalna pr?dko?? w g?r? 1,15.
- Breeze: migracja poprzednich domy?lnych ustawie? do rewizji 5 z kopi? pliku .before-balance-5.bak. W?asne warto?ci administratora pozostaj? zachowane.
- Elytra: p?ytki w miejscach wskazanych jako bramki nie przerywaj? ju? przygotowania wyj?tkiem ?gate is not openable?. Istniej?ce bramki nadal si? otwieraj? i s? przywracane po rundzie.

Wdro?enie: podmie? HexMinigames-1.0.0.jar i uruchom ponownie serwer. Ocena lotu i podmuch?w wymaga pr?by na arenie. Bez logu zg?oszonego b??du Elytry nie mo?na potwierdzi?, ?e wyj?tek bramek by? jego jedyn? przyczyn?.
