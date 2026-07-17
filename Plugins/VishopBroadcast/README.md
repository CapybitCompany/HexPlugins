# VishopBroadcast 1.21.11

VishopBroadcast działa w dwóch warstwach:

- `VishopBroadcastProxy.jar` na **jednym proxy Velocity** przyjmuje komendę ViShop i zapisuje zakup do bazy dokładnie raz;
- `VishopBroadcast-1.0.0.jar` na każdym podserwerze **tylko odczytuje** nowe logi przez HexCore i pokazuje skonfigurowany komunikat graczom.

Podserwery nie tworzą tabel, nie zapisują zakupów, nie aktualizują sum i nie czyszczą logów. Polling pozostaje niezależny na każdym podserwerze i domyślnie odbywa się co 15 sekund. Kursor po `id` gwarantuje, że wszystkie rekordy pobrane w jednym cyklu trafią do lokalnej kolejki komunikatów.

## Wdrożenie

1. Zbuduj oba artefakty:

```powershell
.\gradlew.bat :plugins:VishopBroadcast:build :plugins:VishopBroadcastProxy:build
```

2. Na proxy Velocity umieść:

```text
Plugins/VishopBroadcastProxy/build/libs/VishopBroadcastProxy.jar
```

3. Uruchom proxy raz, uzupełnij dane wspólnej bazy w `plugins/vishopbroadcastproxy/config.yml` i zrestartuj proxy. Writer utworzy lub zmigruje tabele.

4. Na każdym podserwerze Purpur 1.21.11 umieść:

```text
Plugins/VishopBroadcast/build/libs/VishopBroadcast-1.0.0.jar
```

Podserwery wymagają HexCore połączonego z tą samą bazą. Nazwa `tables.purchase-logs` w ich `config.yml` musi odpowiadać `tables.purchase-logs` na proxy.

5. Integrację ViShop skieruj wyłącznie do konsoli proxy:

```text
/vishopbroadcast {nick} Vip - 19.99
/vishopbroadcast {nick} SVIP - 29.99
/vishopbroadcast {nick} Elita - 49.99
/vishopbroadcast {nick} Coins 1000 19.99
/vishopbroadcast {nick} Dar 50
```

Opcjonalny piąty argument jest stabilnym ID transakcji:

```text
/vishopbroadcast {nick} Vip - 19.99 {transaction_id}
```

Bez ID writer zachowuje krótkie okno deduplikacji, które chroni przed ponowieniem tej samej komendy przez ViShop. Nie służy ono już do maskowania pięciu zapisów z podserwerów.

## Konfiguracja

- konfiguracja proxy zawiera połączenie DB, usługi, deduplikację i nocne czyszczenie;
- konfiguracja podserwera zawiera polling i wygląd komunikatów;
- klucze usług (`Vip`, `SVIP`, `Elita`, `Coins`, `Dar`) powinny być takie same po obu stronach;
- `/vishopbroadcast reload` na proxy przeładowuje writer, a na podserwerze przeładowuje wyłącznie lokalny reader i format komunikatów.

## Zachowanie po restarcie

Przy domyślnym `settings.skip-existing-logs-on-startup: true` podserwer ustawia kursor na najnowszym istniejącym logu i pokazuje dopiero kolejne zakupy. Ustawienie `false` odtwarza logi od początku tabeli; zwykle nie jest zalecane na produkcji.
