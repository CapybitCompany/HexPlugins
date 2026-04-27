package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;

public interface PlaceholderProvider {

    String getIdentifier();

    String resolve(HexPlaceholderContext context);
}

