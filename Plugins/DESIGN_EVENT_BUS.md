# DESIGN: HexCore Message Bus — generyczna komunikacja między pluginami

## 1. Problem

Pluginy muszą wymieniać dane (np. WaterLevelFlood informuje o poziomie wody,
WaterDrawn reaguje). Wymagania:
- **Pluginy NIE znają się nawzajem** — zero depend/softdepend/compileOnly na siebie.
- **HexCore jest jedynym pośrednikiem** — gotowy mechanizm komunikacji.
- **Komunikacja przez klucze stringowe** — nie klasy Java.
- **HexCore nie zna treści wiadomości** — jest tylko transportem.
- **Złożone obiekty** muszą być obsługiwane bez importu klas nadawcy.

---

## 2. Koncepcja: Message Bus oparty na kluczach + zagnieżdżone dane

### Analogia radiowa
- Plugin **nadaje** na kanale (klucz stringowy, np. `"flood.water-level"`).
- Plugin **nasłuchuje** kanałów i reaguje, jeśli rozumie klucz.
- HexCore to radio — przekazuje, nie interpretuje.

### Struktura wiadomości
- `String channel` — klucz kanału (np. `"flood.water-level"`)
- `String sender` — nazwa pluginu nadawcy
- `HexMessageData data` — zagnieżdżona mapa klucz-wartość (obsługuje sekcje i listy)

### Dlaczego nie `Map<String, Object>`?
`Map<String, Object>` technicznie pozwala wrzucić dowolny obiekt Java,
ale odbiorca musiałby znać klasę tego obiektu (importować ją z nadawcy).
To łamie zasadę niezależności.

Zamiast tego dostarczamy `HexMessageData` — wrapper, który:
- przechowuje dane wyłącznie jako **prymitywy, stringi, listy i zagnieżdżone sekcje**,
- daje typed getters z default value (`getInt`, `getString`, `getSection`, `getStringList`...),
- pozwala budować złożone struktury bez importu żadnych klas nadawcy,
- jest immutable po zbudowaniu.

---

## 3. Nowe pliki w HexCore (pakiet `hex.core.api.messaging`)

### 3.1 `hex/core/api/messaging/HexMessageData.java`

```java
package hex.core.api.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kontener danych wiadomości — zagnieżdżona mapa obsługująca
 * prymitywy, stringi, listy i sekcje (pod-mapy).
 *
 * Immutable po zbudowaniu. Żadnych klas spoza JDK.
 *
 * Przykład budowania złożonych danych:
 *
 *   HexMessageData data = HexMessageData.builder()
 *       .put("world", "world")
 *       .put("current_level", 50)
 *       .putSection("region", HexMessageData.builder()
 *           .put("x1", -100)
 *           .put("z1", -100)
 *           .put("x2", 100)
 *           .put("z2", 100)
 *           .build())
 *       .putStringList("affected_players", List.of("Steve", "Alex"))
 *       .putSectionList("zones", List.of(
 *           HexMessageData.builder().put("name", "docks").put("y", 55).build(),
 *           HexMessageData.builder().put("name", "bridge").put("y", 62).build()
 *       ))
 *       .build();
 *
 * Przykład odczytu:
 *
 *   int level = data.getInt("current_level", 0);
 *   HexMessageData region = data.getSection("region");
 *   int x1 = region.getInt("x1", 0);
 *   List<String> players = data.getStringList("affected_players");
 *   List<HexMessageData> zones = data.getSectionList("zones");
 */
public final class HexMessageData {

    public static final HexMessageData EMPTY = new HexMessageData(Map.of());

    private final Map<String, Object> values;

    private HexMessageData(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    // ========== Typed getters ==========

    public String getString(String key, String def) {
        Object v = values.get(key);
        return v instanceof String s ? s : (v != null ? String.valueOf(v) : def);
    }

    public int getInt(String key, int def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public long getLong(String key, long def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public double getDouble(String key, double def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public boolean getBool(String key, boolean def) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    // ========== Zagnieżdżone sekcje ==========

    public HexMessageData getSection(String key) {
        Object v = values.get(key);
        return v instanceof HexMessageData d ? d : EMPTY;
    }

    // ========== Listy ==========

    public List<String> getStringList(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item != null ? String.valueOf(item) : "");
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    public List<Integer> getIntList(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> list) {
            List<Integer> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item instanceof Number n ? n.intValue() : 0);
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    public List<HexMessageData> getSectionList(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> list) {
            List<HexMessageData> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(item instanceof HexMessageData d ? d : EMPTY);
            }
            return Collections.unmodifiableList(result);
        }
        return List.of();
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder() {}

        public Builder put(String key, String value)  { data.put(key, value); return this; }
        public Builder put(String key, int value)     { data.put(key, value); return this; }
        public Builder put(String key, long value)    { data.put(key, value); return this; }
        public Builder put(String key, double value)  { data.put(key, value); return this; }
        public Builder put(String key, boolean value) { data.put(key, value); return this; }

        public Builder putSection(String key, HexMessageData section) {
            data.put(key, section);
            return this;
        }

        public Builder putStringList(String key, List<String> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public Builder putIntList(String key, List<Integer> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public Builder putSectionList(String key, List<HexMessageData> list) {
            data.put(key, List.copyOf(list));
            return this;
        }

        public HexMessageData build() {
            return new HexMessageData(new LinkedHashMap<>(data));
        }
    }
}
```

### 3.2 `hex/core/api/messaging/HexMessage.java`

```java
package hex.core.api.messaging;

/**
 * Wiadomość przesyłana przez HexMessageBus.
 * Immutable. Zawiera kanał, nadawcę i dane.
 *
 * Nadawca:
 *   bus.publish(HexMessage.of("flood.water-level", "WaterLevelFlood",
 *       HexMessageData.builder()
 *           .put("world", "world")
 *           .put("current_level", 50)
 *           .build()));
 *
 * Odbiorca:
 *   bus.subscribe("flood.water-level", msg -> {
 *       int level = msg.data().getInt("current_level", 0);
 *   });
 */
public record HexMessage(String channel, String sender, HexMessageData data) {

    public static HexMessage of(String channel, String sender, HexMessageData data) {
        return new HexMessage(channel, sender, data);
    }

    /** Convenience: wiadomość bez danych (np. sygnał "flood.stopped"). */
    public static HexMessage signal(String channel, String sender) {
        return new HexMessage(channel, sender, HexMessageData.EMPTY);
    }
}
```

### 3.3 `hex/core/api/messaging/HexMessageListener.java`

```java
package hex.core.api.messaging;

@FunctionalInterface
public interface HexMessageListener {
    void onMessage(HexMessage message);
}
```

### 3.4 `hex/core/api/messaging/HexMessageBus.java`

```java
package hex.core.api.messaging;

/**
 * Generyczny message bus oparty na kanałach (stringi).
 * Pluginy nie muszą znać się nawzajem.
 *
 * Dostęp:
 *   api.service(HexMessageBus.class).ifPresent(bus -> { ... });
 */
public interface HexMessageBus {

    void subscribe(String channel, HexMessageListener listener);

    void unsubscribe(String channel, HexMessageListener listener);

    void publish(HexMessage message);
}
```

### 3.5 Implementacja: `hex/core/service/messaging/HexMessageBusImpl.java`

```java
package hex.core.service.messaging;

import hex.core.api.messaging.HexMessage;
import hex.core.api.messaging.HexMessageBus;
import hex.core.api.messaging.HexMessageListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HexMessageBusImpl implements HexMessageBus {

    private static final Logger LOG = Logger.getLogger("HexCore-MessageBus");

    private final Map<String, List<HexMessageListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void subscribe(String channel, HexMessageListener listener) {
        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void unsubscribe(String channel, HexMessageListener listener) {
        List<HexMessageListener> list = listeners.get(channel);
        if (list != null) list.remove(listener);
    }

    @Override
    public void publish(HexMessage message) {
        List<HexMessageListener> list = listeners.get(message.channel());
        if (list == null || list.isEmpty()) return;

        for (HexMessageListener listener : list) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                    "[HexMessageBus] Error in listener on '" + message.channel()
                    + "' from '" + message.sender() + "'", e);
            }
        }
    }
}
```

### 3.6 Rejestracja w `HexCore.java` onEnable()

Po `this.api = apiImpl;` dodać jedną linię:

```java
apiImpl.registerService(HexMessageBus.class, new HexMessageBusImpl());
```

**To jedyna zmiana w HexCore. Zero wiedzy o kanałach, pluginach, danych.**

---

## 4. Przykłady złożonych danych

### 4.1 Proste dane (WaterLevelFlood → WaterDrawn)

```java
// NADAWCA (WaterLevelFlood):
bus.publish(HexMessage.of("flood.water-level", "WaterLevelFlood",
    HexMessageData.builder()
        .put("world", "world")
        .put("previous_level", 49)
        .put("current_level", 50)
        .build()));

// ODBIORCA (WaterDrawn):
bus.subscribe("flood.water-level", msg -> {
    int level = msg.data().getInt("current_level", 0);
});
```

### 4.2 Zagnieżdżone sekcje (region z wieloma polami)

```java
// NADAWCA (np. IcebergEvent informuje o kolizji):
bus.publish(HexMessage.of("iceberg.collision", "IcebergEvent",
    HexMessageData.builder()
        .put("world", "world")
        .put("timestamp", System.currentTimeMillis())
        .putSection("impact_point", HexMessageData.builder()
            .put("x", 45).put("y", 62).put("z", -120)
            .build())
        .putSection("iceberg_bounds", HexMessageData.builder()
            .put("x1", 38).put("z1", -128)
            .put("x2", 52).put("z2", -112)
            .build())
        .build()));

// ODBIORCA (np. AreaEffects):
bus.subscribe("iceberg.collision", msg -> {
    HexMessageData point = msg.data().getSection("impact_point");
    int x = point.getInt("x", 0);
    int y = point.getInt("y", 64);
    int z = point.getInt("z", 0);
});
```

### 4.3 Listy sekcji (wiele aren, wiele graczy)

```java
// NADAWCA (Minigames):
bus.publish(HexMessage.of("minigames.arena-status", "Minigames",
    HexMessageData.builder()
        .put("game_type", "skywars")
        .putSectionList("arenas", List.of(
            HexMessageData.builder()
                .put("id", "sw01").put("state", "INGAME").put("players", 8).build(),
            HexMessageData.builder()
                .put("id", "sw02").put("state", "LOBBY").put("players", 3).build()
        ))
        .build()));

// ODBIORCA (Hub):
bus.subscribe("minigames.arena-status", msg -> {
    for (HexMessageData arena : msg.data().getSectionList("arenas")) {
        String id = arena.getString("id", "?");
        String state = arena.getString("state", "?");
        int players = arena.getInt("players", 0);
    }
});
```

### 4.4 Sygnały bez danych

```java
bus.publish(HexMessage.signal("flood.stopped", "WaterLevelFlood"));
```

---

## 5. Zmiany w WaterLevelFlood (NADAWCA)

### 5.1 `build.gradle`
```groovy
compileOnly project(':plugins:HexCore')
```

### 5.2 `plugin.yml`
```yaml
softdepend:
  - HexCore
```

### 5.3 `FloodManager.java` — po `currentWaterLevel++`

```java
currentWaterLevel++;
publishWaterLevelChanged(currentWaterLevel - 1, currentWaterLevel);
fillSingleLayerAsync(currentWaterLevel, true, null);
```

Nowa metoda:
```java
private void publishWaterLevelChanged(int previousLevel, int newLevel) {
    try {
        var reg = Bukkit.getServicesManager()
                .getRegistration(hex.core.api.HexApi.class);
        if (reg == null) return;
        reg.getProvider()
           .service(hex.core.api.messaging.HexMessageBus.class)
           .ifPresent(bus -> bus.publish(
               hex.core.api.messaging.HexMessage.of(
                   "flood.water-level", "WaterLevelFlood",
                   hex.core.api.messaging.HexMessageData.builder()
                       .put("world", config.getWorldName())
                       .put("previous_level", previousLevel)
                       .put("current_level", newLevel)
                       .build())
           ));
    } catch (NoClassDefFoundError | Exception ignored) {}
}
```

---

## 6. Zmiany w WaterDrawn (ODBIORCA)

### 6.1 `build.gradle`
```groovy
compileOnly project(':plugins:HexCore')
```
**(NIE compileOnly na WaterLevelFlood!)**

### 6.2 `plugin.yml`
```yaml
softdepend:
  - HexCore
```
**(NIE softdepend na WaterLevelFlood!)**

### 6.3 Nowa klasa: `hex/drawn/integration/HexMessageBridge.java`

```java
package hex.drawn.integration;

import hex.drawn.WaterDrawnPlugin;
import org.bukkit.Bukkit;

public class HexMessageBridge {
    private static final String CHANNEL = "flood.water-level";
    private final WaterDrawnPlugin plugin;
    private hex.core.api.messaging.HexMessageListener listener;

    public HexMessageBridge(WaterDrawnPlugin plugin) { this.plugin = plugin; }

    public void trySubscribe() {
        try {
            if (Bukkit.getPluginManager().getPlugin("HexCore") == null) return;
            var reg = Bukkit.getServicesManager()
                    .getRegistration(hex.core.api.HexApi.class);
            if (reg == null) return;
            reg.getProvider().service(hex.core.api.messaging.HexMessageBus.class)
               .ifPresent(bus -> {
                   this.listener = msg -> {
                       int level = msg.data().getInt("current_level", Integer.MAX_VALUE);
                       plugin.setRuntimeWaterLevel(level);
                   };
                   bus.subscribe(CHANNEL, listener);
                   plugin.getLogger().info("[MessageBus] Subscribed to '" + CHANNEL + "'.");
               });
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().info("[MessageBus] Skipped: " + e.getMessage());
        }
    }

    public void tryUnsubscribe() {
        if (listener == null) return;
        try {
            var reg = Bukkit.getServicesManager()
                    .getRegistration(hex.core.api.HexApi.class);
            if (reg == null) return;
            reg.getProvider().service(hex.core.api.messaging.HexMessageBus.class)
               .ifPresent(bus -> bus.unsubscribe(CHANNEL, listener));
        } catch (Exception ignored) {}
    }
}
```

### 6.4–6.7 Zmiany w WaterDrawnPlugin, WaterDrawnConfig, DrownListener

Patrz sekcja 6 w szczegółach poniżej checklisty.

---

## 7. Diagram przepływu

```
WaterLevelFlood                     HexCore                          WaterDrawn
(nadawca)                     (message bus)                      (odbiorca)
nie zna WaterDrawn          nie zna obu pluginów              nie zna WaterLevelFlood
     |                               |                                |
     | bus.publish(                   |                                |
     |   channel="flood.water-level" |                                |
     |   data={current_level=50}     |                                |
     | )                             |                                |
     |------------------------------>|---listener.onMessage(msg)----->|
     |                               |         msg.data().getInt(...)  |
     |                               |         → setDynamicWaterLevel  |
```

---

## 8. Gdzie żyją klasy

| Klasa | Plugin | Zależy od... |
|-------|--------|--------------|
| `HexMessageData` | HexCore | nic |
| `HexMessage` | HexCore | nic |
| `HexMessageBus` | HexCore | nic |
| `HexMessageListener` | HexCore | nic |
| `HexMessageBusImpl` | HexCore | nic |
| `FloodManager` (publish) | WaterLevelFlood | HexCore API |
| `HexMessageBridge` | WaterDrawn | HexCore API |

---

## 9. Konwencja kanałów (dokumentacja, nie kod)

| Kanał | Nadawca | Klucze | Opis |
|-------|---------|--------|------|
| `flood.water-level` | WaterLevelFlood | `world`, `previous_level`, `current_level` | Woda wzrosła |
| `flood.started` | WaterLevelFlood | `world`, `start_level`, `target_level` | Start |
| `flood.stopped` | WaterLevelFlood | *(sygnał)* | Stop |
| `iceberg.collision` | IcebergEvent | `world`, sekcja `impact_point`, sekcja `bounds` | Kolizja |
| `elimination.killed` | HexElimination | `player`, `uuid` | Eliminacja |
| `minigames.arena-status` | Minigames | `game_type`, lista sekcji `arenas` | Status aren |

---

## 10. Dlaczego tak

| Decyzja | Uzasadnienie |
|---------|-------------|
| Klucze stringowe, nie klasy Java | Zero importów między pluginami |
| `HexMessageData` zamiast `Map<String,Object>` | Type-safe, zagnieżdżone sekcje, listy — bez castów i obcych klas |
| Typed builder (`put(String,int)` etc.) | Nie da się wrzucić obiektu — tylko prymitywy, stringi, sekcje, listy |
| Immutable `HexMessageData` | Odbiorca nie modyfikuje danych nadawcy |
| `softdepend` tylko na HexCore | Plugin działa samodzielnie |
| Error handling per listener | Zepsuty odbiorca nie blokuje reszty |

---

## 11. Checklist implementacji

### HexCore (5 nowych plików + 1 linia):
- [ ] `hex/core/api/messaging/HexMessageData.java`
- [ ] `hex/core/api/messaging/HexMessage.java`
- [ ] `hex/core/api/messaging/HexMessageBus.java`
- [ ] `hex/core/api/messaging/HexMessageListener.java`
- [ ] `hex/core/service/messaging/HexMessageBusImpl.java`
- [ ] `HexCore.java`: `apiImpl.registerService(HexMessageBus.class, new HexMessageBusImpl())`

### WaterLevelFlood (3 pliki):
- [ ] `build.gradle`: `compileOnly project(':plugins:HexCore')`
- [ ] `plugin.yml`: `softdepend: [HexCore]`
- [ ] `FloodManager.java`: `publishWaterLevelChanged()`

### WaterDrawn (1 nowa klasa + 4 pliki):
- [ ] `hex/drawn/integration/HexMessageBridge.java`
- [ ] `build.gradle`: `compileOnly project(':plugins:HexCore')` (NIE WaterLevelFlood)
- [ ] `plugin.yml`: `softdepend: [HexCore]` (NIE WaterLevelFlood)
- [ ] `WaterDrawnPlugin.java`: pola + setter + bridge
- [ ] `WaterDrawnConfig.java`: `dynamicWaterLevel` + `getEffectiveDrownWaterLevelY()`
- [ ] `DrownListener.java`: `→ getEffectiveDrownWaterLevelY()`
- [ ] `WaterDrawnConfig.isExcludedAt()`: `→ getEffectiveDrownWaterLevelY()`
