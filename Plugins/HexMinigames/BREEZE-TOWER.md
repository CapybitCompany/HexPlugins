# Wieża Breeze’a

Identyfikator gry: `breeze_tower`. Runda trwa 60 sekund, poprzedzonych 20 sekundami zasad.

- Region: od `117 -33 -151` do `-32 47 -7`.
- Spawn podczas zasad: `78 9 -76`, zachód (yaw 90).
- Spawn na rozpoczęcie gry: `39 -2 -77`.
- Breezy: `29 2 -59`, `29 1 -94`, `51 2 -94`, `51 3 -60`.
- Eliminacja przy `Y <= -13`: efekt wybuchu, spectator, teleport do `38 8 -76`.
- Punkty za czas przetrwania: poniżej 20 s — 0; od 20 s — 1; od 40 s — 2; pełne 60 s — 3. Progi nie sumują się.

Breezy mają wyłączone AI, grawitację, kolizję i usuwanie w trybie Peaceful. Strzelają kolejno, jeden co 1,5 s, z pierwszym strzałem 2 s po starcie. Pociski celują w aktualną pozycję aktywnego gracza; przy wielu graczach kolejne strzały nie wybierają tej samej osoby. PvP i obrażenia zdrowia są wyłączone. Podmuch korzysta z fizyki wind charge’a, a eksplozje nie modyfikują bloków areny. Jeśli plugin ochronny zablokuje spawn lub usunie NPC, runda zostaje przerwana z komunikatem diagnostycznym w konsoli.

W `games/breeze_tower.yml` można regulować:

| Klucz w settings | Domyślnie | Znaczenie |
| --- | --- | --- |
| `shots.interval-ticks` | 30 | Odstęp między strzałami wszystkich Breeze’ów łącznie |
| `shots.first-delay-ticks` | 40 | Opóźnienie pierwszego strzału |
| `shots.speed` | 0.85 | Początkowa prędkość pocisku |
| `shots.lifetime-ticks` | 100 | Maksymalny czas lotu pocisku |
| `elimination-y` | -13 | Graniczna wysokość eliminacji |

20 ticków odpowiada jednej sekundzie przy normalnej szybkości serwera.

## Wgranie

Podmień JAR oraz dotychczasowy pusty plik `plugins/HexMinigames/games/breeze_tower.yml` na wersję z projektu. Zrestartuj serwer. Istniejące YAML-e nie są automatycznie nadpisywane.

Test administratora: `/hexminigames testareny breeze_tower Nick`.

Testy automatyczne obejmują konfigurację, progi punktowe, kolejność strzałów, eliminację, blokadę PvP i sprzątanie encji. Siła i odczuwalne tempo natywnych podmuchów wymagają oceny na działającej arenie.

Implementacja pocisków opiera się na [API AbstractWindCharge w Paper](https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/AbstractWindCharge.html).
