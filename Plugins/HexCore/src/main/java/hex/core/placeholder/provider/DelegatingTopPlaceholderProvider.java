package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;

/**
 * Legacy adapter kept for backwards compatibility.
 *
 * NOTE: Current implementation supports prefix registration in HexPlaceholderRegistry,
 * so this class is no longer required. It delegates to TopRankingPlaceholderProvider#resolve.
 */
public final class DelegatingTopPlaceholderProvider implements PlaceholderProvider {

    private final String identifier;
    private final TopRankingPlaceholderProvider delegate;

    public DelegatingTopPlaceholderProvider(String identifier, TopRankingPlaceholderProvider delegate) {
        this.identifier = identifier;
        this.delegate = delegate;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        // Delegate expects the full identifier to be present in context.identifier().
        return delegate.resolve(new HexPlaceholderContext(context.api(), context.player(), identifier));
    }
}
