package hexposterunki.engine;

/**
 * Extension point for later mob-count scaling.
 *
 * <p>Version 1 deliberately ships only {@link #none()}: the configured counts are used as-is.
 * The engine still routes every count through this interface, so adding a real strategy later is
 * a one-line change instead of a rewrite of the wave logic.
 */
@FunctionalInterface
public interface WaveScaling {

    int scale(int baseCount, int participants);

    static WaveScaling none() {
        return (baseCount, participants) -> baseCount;
    }
}
