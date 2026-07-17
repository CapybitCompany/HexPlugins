# HexEconomy

## PlaceholderAPI

HexEconomy automatycznie rejestruje ekspansję `hexeconomy`, jeśli PlaceholderAPI jest obecne na serwerze.

Stan konta gracza w bieżącym kontekście:

- `%hexeconomy_balance%` — kwota bez nazwy waluty, np. `1250.00`
- `%hexeconomy_balance_formatted%` — kwota według `currency.format`
- `%hexeconomy_currency%` — nazwa waluty

Ranking pięciu najbogatszych kont używa schematu zgodnego z rankingami HexCore. Dla pozycji od 1 do 5 dostępne są:

- `%hexeconomy_top_money_1_name%`
- `%hexeconomy_top_money_1_amount%`
- `%hexeconomy_top_money_1_formatted%`

Numer `1` można zastąpić wartościami `2`, `3`, `4` lub `5`. Brakująca pozycja zwraca `-`.
