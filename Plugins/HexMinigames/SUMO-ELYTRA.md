# SUMO i Elytra

SUMO (`monkey_run`) trwa 90 sekund. Czas nalicza się indywidualnie na centralnej arenie; upadek zachowuje postęp. Progi punktów: 20 sekund = 1, 40 = 2, 60 = 3. Płytki po obu stronach kierują graczy wysokim łukiem na środek.

## Wymagana konfiguracja Elytry

W `plugins/HexMinigames/games/elytra.yml` uzupełnij `settings.ring-order` listą współrzędnych wszystkich bloków PINK_CONCRETE w kolejności przelotu. Każdy element listy ma pola `x`, `y`, `z`. Lista pusta oznacza niedostępną grę. Kolejność nie jest zgadywana z geometrii mapy.

Spawn ustawiono na potwierdzone 276 -21 55. Domyślna lista zawiera 21 różnych markerów odczytanych ze screenów, w kolejności pierwszy screen, następnie drugi od góry. Skan regionu porównuje rzeczywiste różowe bloki z listą i zgłasza ich liczbę, brakujące współrzędne oraz duplikaty przed usunięciem markerów. Nie dodaje brakujących obręczy w zgadywanej kolejności. Migracja uzupełnia pustą listę i zmienia stary domyślny spawn, zachowując własne ustawienia i kopię poprzedniego pliku.

Każda maska musi być spójna po ścianach bloków, płaska i zawierać dokładnie jeden różowy marker. Wszystkie żółte i różowe markery w regionie muszą należeć do masek. Przelot jest sprawdzany segmentem ruchu przez dokładne komórki maski, osobno dla orientacji każdej obręczy. Obramowanie zaliczonych obręczy jest zielone tylko dla danego gracza.

Przed usunięciem markerów plugin zapisuje ich oryginalne dane oraz dane furtek w `elytra-marker-recovery.yml` w katalogu pluginu. Przy zakończeniu przywraca bloki; po przerwaniu procesu odzyskuje je przy uruchomieniu. Nie usuwaj tego pliku podczas odzyskiwania mapy. Reload konfiguracji kończy aktywną Elytrę i przywraca markery przed wczytaniem nowych ustawień.

Runda trwa 90 sekund. Punktacja ukończenia: pierwsza osoba 3, miejsca 2–5 po 2, miejsca 6–8 po 1, pozostali 0. Lądowanie lub upadek zachowuje zaliczone obręcze.

## Pozostałe poprawki

- Breeze: `settings.shots.knockback-multiplier: 1.8` i `settings.shots.minimum-upward-velocity: 0.95` w `games/breeze_tower.yml`.
- Szklany most: wadliwe szkło znika przy kontakcie, skok z niego jest anulowany; samo nadepnięcie nie powoduje eksplozji ani eliminacji.
- Disco: różowy beton uczestniczy w losowaniu i usuwaniu podłogi. Odliczanie koloru jest białe.
- HotHead: maksymalna wysokość obszaru duszków to Y=-30.
- Zatrzymanie podczas wyników nadaje graczom uprawnienie lotu. Zewnętrzny antycheat może wymagać własnej konfiguracji.

Weryfikacja: testy automatyczne i kompilacja JAR. Siła wyrzutów oraz zachowanie klientów wymagają testu na działającym serwerze.
