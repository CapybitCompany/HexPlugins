# HexEndEvent

Cykliczny event Endu dla Hex SMP.

- twarda blokada dostępu poza aktywnym eventem,
- harmonogram z `config.yml`,
- automatyczny reset Endu przed kolejnym eventem,
- ewakuacja graczy po zakończeniu,
- BossBar czasu w Endzie,
- `/endevent`,
- PlaceholderAPI `%hexendevent_*%`,
- komunikaty przez HexCore UiService.

Domyślnie `event.enabled: true`; o faktycznym otwarciu Endu decyduje harmonogram.
