package hexposterunki.towns;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Version-checked reflection bridge to the {@code TownsApi} published by HexTowns through the
 * Bukkit {@code ServicesManager}.
 *
 * <p>HexTowns is intentionally not a compile-time dependency of this branch. To stay honest about
 * that, the adapter calls exactly two API methods - {@code townIdOf(UUID)} and {@code findTown(UUID)} -
 * plus {@code Town.name()}. It never reflects into internal services, private fields or the
 * plugin instance. If any of those shapes is missing, the adapter binds as unavailable with a
 * readable reason instead of failing later at an arbitrary call site.
 */
public final class ReflectionTownsAdapter implements TownsAdapter {

    private static final String API_CLASS = "hex.towns.api.TownsApi";
    private static final String TOWN_CLASS = "hex.towns.model.Town";

    private final Logger logger;
    private final String requiredVersion;

    private Object townsApi;
    private Method townIdOfMethod;
    private Method findTownMethod;
    private Method townNameMethod;
    private String status = "niezainicjalizowane";

    public ReflectionTownsAdapter(Logger logger, String requiredVersion) {
        this.logger = logger;
        this.requiredVersion = requiredVersion == null || requiredVersion.isBlank() ? "*" : requiredVersion.trim();
        bind();
    }

    /** Re-resolves the binding; used by {@code /posterunki reload} and {@code validate}. */
    public boolean bind() {
        townsApi = null;
        townIdOfMethod = null;
        findTownMethod = null;
        townNameMethod = null;

        Plugin towns = Bukkit.getPluginManager().getPlugin("HexTowns");
        if (towns == null || !towns.isEnabled()) {
            status = "HexTowns nie jest włączone";
            return false;
        }
        if (!"*".equals(requiredVersion) && !requiredVersion.equals(towns.getDescription().getVersion())) {
            status = "Oczekiwano HexTowns " + requiredVersion + ", znaleziono " + towns.getDescription().getVersion();
            return false;
        }

        try {
            Class<?> apiClass = Class.forName(API_CLASS, false, towns.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null || registration.getProvider() == null) {
                status = "TownsApi nie jest zarejestrowane w ServicesManager";
                return false;
            }
            Object provider = registration.getProvider();

            Method townIdOf = apiClass.getMethod("townIdOf", UUID.class);
            Method findTown = apiClass.getMethod("findTown", UUID.class);
            if (!Optional.class.equals(townIdOf.getReturnType()) || !Optional.class.equals(findTown.getReturnType())) {
                status = "Niezgodne API HexTowns: townIdOf/findTown nie zwracają Optional";
                return false;
            }
            Class<?> townClass = Class.forName(TOWN_CLASS, false, towns.getClass().getClassLoader());
            Method name = townClass.getMethod("name");
            if (!String.class.equals(name.getReturnType())) {
                status = "Niezgodne API HexTowns: Town.name() nie zwraca String";
                return false;
            }

            this.townsApi = provider;
            this.townIdOfMethod = townIdOf;
            this.findTownMethod = findTown;
            this.townNameMethod = name;
            this.status = "HexTowns " + towns.getDescription().getVersion() + " podłączone";
            return true;
        } catch (ClassNotFoundException exception) {
            status = "Brak klasy HexTowns: " + exception.getMessage();
        } catch (NoSuchMethodException exception) {
            status = "Niezgodne API HexTowns: brak metody " + exception.getMessage();
        } catch (Throwable error) {
            status = "Błąd wiązania HexTowns: " + error.getMessage();
        }
        if (logger != null) {
            logger.severe("[towns] " + status);
        }
        return false;
    }

    @Override
    public boolean available() {
        return townsApi != null && townIdOfMethod != null && findTownMethod != null && townNameMethod != null;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public Optional<UUID> townIdOf(UUID playerId) {
        if (!available() || playerId == null) {
            return Optional.empty();
        }
        try {
            Object result = townIdOfMethod.invoke(townsApi, playerId);
            if (result instanceof Optional<?> optional) {
                return optional.filter(UUID.class::isInstance).map(UUID.class::cast);
            }
            return Optional.empty();
        } catch (Throwable error) {
            warnOnce("townIdOf", error);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> townName(UUID townId) {
        if (!available() || townId == null) {
            return Optional.empty();
        }
        try {
            Object result = findTownMethod.invoke(townsApi, townId);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object town = optional.get();
            Object name = townNameMethod.invoke(town);
            return name == null ? Optional.empty() : Optional.of(String.valueOf(name));
        } catch (Throwable error) {
            warnOnce("findTown", error);
            return Optional.empty();
        }
    }

    private void warnOnce(String call, Throwable error) {
        if (logger != null) {
            logger.warning("[towns] Wywołanie " + call + " nie powiodło się: " + error.getMessage());
        }
    }
}
