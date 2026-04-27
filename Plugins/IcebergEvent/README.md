# IcebergEvent

`IcebergEvent` tworzy i przesuwa górę lodową z punktu start do punktu docelowego.
Po dotarciu do celu góra znika po czasie ustawionym w konfiguracji.

## Co robi plugin
- generuje bryłę góry lodowej,
- przesuwa ją krokowo po trasie `start -> target`,
- po zakończeniu animacji usuwa górę z mapy.

## Komendy (admin)
- `/iceberg start` - startuje event góry lodowej.
- `/iceberg stop` - zatrzymuje event i czyści aktywną górę.
- `/iceberg status` - pokazuje, czy event działa i na jakim etapie jest.
- `/iceberg reload` - przeładowuje `config.yml`.

## Dla kogo
- dla administratora/event managera, który uruchamia scenariusz „Titanic”,
- dla osoby konfigurującej trasę i tempo ruchu góry lodowej.

## Najważniejsze pliki
- `src/main/resources/config.yml` - pozycja start/koniec, czas ruchu, opóźnienie zniknięcia,
- `src/main/resources/plugin.yml` - komendy i uprawnienia.
