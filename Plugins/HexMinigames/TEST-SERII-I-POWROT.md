# Test serii, powrót i rezygnacja

- `/hexminigames test Gracz1 Gracz2` uruchamia serię pięciu różnych gier losowanych z dostępnej puli dla wybranej liczby uczestników. Nazwy można rozdzielać również przecinkami. Komenda działa z konsoli; bez nazw gracz uruchamia test dla siebie.
- `/hexminigames testareny <gra> <gracze...>` nadal testuje pojedynczą grę.
- W poczekalni i podczas podsumowania można skakać.
- Po podsumowaniu (domyślnie 12 sekund, `durations.series-results-seconds`) uczestnicy wracają do zapisanej pozycji i świata sprzed dołączenia, razem z zapisanym ekwipunkiem. Gracz dołączający ze świata `world` wróci na swoją pozycję w `world`.
- Wyjście lub rozłączenie przed podsumowaniem zeruje punkty bieżącej serii tego gracza i wyklucza je z zapisu globalnego. Punkty z wcześniejszych serii pozostają. Wyjście podczas końcowego podsumowania nie odbiera już zdobytego wyniku.
- Testy administracyjne nadal nie dopisują punktów do rankingu globalnego.

Wdrożenie: podmień HexMinigames-1.0.0.jar i zrestartuj serwer.
