# Instrukcja dla HexPlugins

Dokument opisuje architekturę, strukturę projektu oraz zasady pracy w mono-repo **HexPlugins** opartym o **Paper 1.21 (Java 21)**.

---

## Struktura kodu

Mono-repo jest projektem wielomodułowym (Gradle – Groovy).
```HexPlugins/
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
├─ plugins/
│ ├─ HexCore/
│ ├─ HexEconomy/
│ ├─ HexSurvival/
│ └─ ...
└─ run/ (lokalny serwer testowy)
```

Każdy plugin jest osobnym modułem Gradle:
```
plugins/HexCore/
├─ build.gradle
└─ src/
└─ main/
├─ java/
└─ resources/
```
---

## Struktura kodu pluginu

Każdy plugin powinien mieć uporządkowaną strukturę pakietów:
```
hex/core/
├─ HexCore.java
├─ command/
├─ listener/
├─ model/
├─ repository/
├─ service/
└─ util/
```
---

### Command

- Jedna komenda = jedna klasa
- Implementuje `CommandExecutor`
- Nie zawiera logiki biznesowej
- Wywołuje metody z `service`

Przykład:

```java
public class HexCoreCommand implements CommandExecutor {
    private final HexCore plugin;

    public HexCoreCommand(HexCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("HexCore wersja: " + plugin.getDescription().getVersion());
        return true;
    }
}
```

### Listener

- Obsługuje eventy Bukkit
- Implementuje Listener
- Rejestrowany w onEnable()
- NIE zawiera logiki biznesowej

Przykład:
```java
public class PlayerJoinListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage("Witaj!");
    }
}
```
### Modeler
-Reprezentują dane domenowe
-Czyste klasy POJO
-Bez zależności od Bukkit API
```java
public class User {
    private UUID uuid;
    private int coins;
}
```
### Repository
- Odpowiada za dostęp do danych
- Komunikacja z bazą danych / plikami
- Brak logiki biznesowej

```java
    public class UserRepository {
        public void save(User user) {
            // zapis do bazy
        }
    }
```
### Services
- Logika biznesowa pluginu
- Operacje na modelach
- Korzystają z repository
- NIE powinny bezpośrednio zależeć od Bukkit API

```java
public class EconomyService {

    private final UserRepository repository;

    public EconomyService(UserRepository repository) {
        this.repository = repository;
    }

    public void addCoins(User user, int amount) {
        user.setCoins(user.getCoins() + amount);
        repository.save(user);
    }
}
```
### Utils
- Klasy pomocnicze
- Statyczne metody
- Formatowanie wiadomości
- Operacje pomocnicze

```java
public class ChatUtil {
    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
```
## HexCore
### Czym jest HexCore

HexCore to centralny plugin bazowy systemu HexPlugins.

Odpowiada za:
- API dla innych pluginów
- Wspólne serwisy
- Integracje (np. bazy danych)
- System rejestracji usług
- Wspólne komendy administracyjne
- HexCore jest fundamentem dla innych pluginów (HexEconomy, HexSurvival itd.).

### Jak używać HexCore
Inne pluginy mogą:
- zależeć od HexCore przez depend: w plugin.yml
- korzystać z publicznego API
- pobierać wersję przez:

```java
HexCore.getInstance().getDescription().getVersion();
```
Rejestracja komendy w HexCore:
```java
getCommand("hexcore").setExecutor(new HexCoreCommand(this));
```
## Zasady pisania w projekcie

1. Java 21 (zgodnie z Paper 1.21)
2. Jedna odpowiedzialność = jedna klasa
3. Logika biznesowa NIE w Command/Listener
4. Dokumentacja klasy/funkcji poprzez /**
5. Brak statycznych instancji, jeśli nie są konieczne
6. Async przy operacjach bazodanowych
7. Brak /reload w produkcji
8. Czytelne nazwy klas i pakietów
9. Każda komenda w osobnej klasie
10. Walidacja argumentów komend
11. Logger zamiast System.out.println

## Zasady tworzenia branchy
### Główne branche

**main** – stabilna wersja produkcyjna

**develop** – aktualny rozwój

### Feature branche

Schemat:
```
feature/nazwa-funkcji
fix/nazwa-buga
refactor/nazwa-zmiany
```
```
feature/economy-system
fix/command-nullpointer
refactor/service-layer
```

## Workflow

1. Tworzymy branch od develop
2. Implementujemy funkcjonalność
3. Pull request do develop
4. Code review
5. Merge


