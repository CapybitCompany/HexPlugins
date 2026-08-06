# HexRandomTP

Plugin RTP dla Purpur/Paper 1.21.11, korzystający z `HexCore` jako wymaganej zależności.

## Funkcje

- `/rtp`, `/randomtp`, `/randomteleport`.
- Asynchroniczne ładowanie chunków i teleportacja, aby ograniczyć przycięcia serwera.
- Opcjonalny zakaz generowania nowych chunków oraz lista dokładnie wykluczonych chunków.
- Losowanie w granicach `min-x/max-x/min-z/max-z`.
- Dowolna lista prostokątnych zakazanych obszarów `x1,z1 -> x2,z2`.
- Teleport na najwyższą bezpieczną powierzchnię lądu.
- Ponowne losowanie dla oceanów, rzek, cieczy, niebezpiecznych bloków i miejsc bez przestrzeni na gracza.
- Respektowanie granicy świata.
- Wykluczanie konkretnych regionów oraz namespace'ów regionów z `HexCore`.
- Cooldown domyślny i krótsze cooldowny zależne od dowolnych uprawnień.
- Jeżeli gracz ma kilka uprawnień cooldownu, wybierany jest najkrótszy czas.
- Cooldown jest naliczany dopiero po udanej teleportacji.
- `/rtp reload` z uprawnieniem `hexrandomtp.admin`.
- RTP uruchamiane przez skonfigurowane przyciski, dźwignie, płyty naciskowe i tripwire.
- Osobny, konfigurowalny prefiks wiadomości HexRandomTP.
- Wiadomości są rejestrowane w UI HexCore i mogą być nadpisane w `plugins/HexCore/ui.yml`.

## Zakazane obszary

W `config.yml` można dodać dowolną liczbę prostokątów. Granice są inkluzywne,
a kolejność narożników nie ma znaczenia:

```yaml
search:
  forbidden-areas:
    - name: spawn
      x1: -500
      z1: -500
      x2: 500
      z2: 500
    - name: event-zone
      x1: 1200
      z1: -800
      x2: 1800
      z2: -200
```

Wylosowanie punktu w takim obszarze powoduje natychmiastową kolejną próbę, jeszcze przed ładowaniem chunka.

## Cooldowny rangowe

```yaml
cooldown:
  default-seconds: 60
  bypass-permission: hexrandomtp.cooldown.bypass
  permission-cooldowns:
    - permission: nte.vip
      seconds: 30
    - permission: nte.svip
      seconds: 20
    - permission: nte.elita
      seconds: 10
```

Nazwy permission i czasy można dowolnie zmieniać. Jeżeli gracz ma kilka pasujących
uprawnień, plugin używa najkrótszego cooldownu. Wartość `0` oznacza brak cooldownu.
Starszy format `cooldown.permission-overrides` pozostaje obsługiwany.

## Aktywatory redstone

Każdy wpis wskazuje dokładną pozycję mechanizmu. `world` jest opcjonalny — bez niego
plugin używa świata z głównego pola `world`. Blok znajdujący się pod wskazaną pozycją
musi być mechanizmem redstone typu `Powerable`, np. przyciskiem, dźwignią, płytą
naciskową lub tripwire:

```yaml
activators:
  positions:
    - world: world
      x: 100
      y: 65
      z: -200
    - x: 105
      y: 65
      z: -200
```

Kliknięcie mechanizmu lub wejście na niego uruchamia dla gracza dokładnie ten sam
przepływ co `/rtp`, włącznie z `hexrandomtp.use`, cooldownem i kontrolą trwającego
wyszukiwania. Anulowane przez inny plugin interakcje są respektowane. Lista może być
zmieniana przez `/rtp reload`.

## Instalacja

1. Zbuduj plugin komendą `./gradlew build` w katalogu `HexRandomTP` albo użyj gotowego `build/libs/HexRandomTP.jar`.
2. Umieść `HexCore.jar` i `HexRandomTP.jar` w katalogu `plugins` serwera.
3. Uruchom serwer i edytuj `plugins/HexRandomTP/config.yml`.
4. Wykonaj `/rtp reload`.

## Uprawnienia

- `hexrandomtp.use` — użycie `/rtp`, domyślnie każdy.
- `hexrandomtp.admin` — `/rtp reload`, domyślnie OP.
- `hexrandomtp.cooldown.bypass` — brak cooldownu, domyślnie OP.
- Przykładowe wpisy domyślne: `nte.vip`, `nte.svip`, `nte.elita`.

## Nadpisywanie wiadomości

Prefiks tego pluginu ustawia się bezpośrednio w `plugins/HexRandomTP/config.yml`.
Obsługiwany jest format MiniMessage, a pusty tekst wyłącza prefiks:

```yaml
messages:
  prefix: "<gray>[</gray><aqua><bold>RTP</bold></aqua><gray>]</gray> "
```

Przykład w `plugins/HexCore/ui.yml`:

```yaml
overrides:
  randomtp.searching: "<gray>Szukam bezpiecznego lądu...</gray>"
  randomtp.success: "<green>Teleport: <yellow><x> <y> <z></yellow></green>"
```
