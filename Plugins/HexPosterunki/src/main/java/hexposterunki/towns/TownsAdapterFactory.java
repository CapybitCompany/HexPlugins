package hexposterunki.towns;

import java.util.logging.Logger;

/**
 * Creates the binding to HexTowns for a given configuration.
 *
 * <p>Production binds {@link ReflectionTownsAdapter}. The factory exists so a reload can build and
 * validate a <i>candidate</i> binding without touching the one the running engine uses, and so the
 * plugin lifecycle can be tested with a {@link TownsAdapter} test double at exactly this boundary.
 */
@FunctionalInterface
public interface TownsAdapterFactory {

    TownsAdapter bind(Logger logger, String requiredVersion);

    static TownsAdapterFactory reflection() {
        return ReflectionTownsAdapter::new;
    }
}
