# HexSequence

Plugin do uruchamiania skonfigurowanych lancuchow komend jedna komenda.

## Komendy

- `/hexsequence <nazwa>` - uruchamia sekwencje z configu
- `/hexsequence reload` - przeladowuje config

Aliasy: `/hexeventsequence`, `/eventsequence`, `/hexsekwencja`.

## Uprawnienia

- `hexsequence.use` - uruchamianie sekwencji
- `hexsequence.admin` - reload configu

## Format configu

```yml
sequences:
  sekwencja:
    - '[console] "/iceberg start"'
    - '[console] 10.3 "/areaeffects start explosion"'
    - '[console] "/say Startuje razem z poprzednia komenda, po 10.3s"'
    - '[player] 12.0 "/say Uzytkownik: %player_name%"'
```

Czas jest opcjonalny i liczony wzgledem poczatku wywolania sekwencji. Wpis bez czasu uzywa ostatnio podanego czasu, a jesli zadnego jeszcze nie bylo - startuje od razu.

`[console]` wykonuje komende z konsoli. `[player]` wykonuje komende jako gracz, ktory uruchomil sekwencje. Jesli sekwencja zawiera `[player]`, nie nalezy uruchamiac jej z konsoli.

