package hex.core.placeholder.provider;

import hex.core.placeholder.HexPlaceholderContext;

public final class CoinsPlaceholderProvider implements PlaceholderProvider {

    @Override
    public String getIdentifier() {
        return "coins";
    }

    @Override
    public String resolve(HexPlaceholderContext context) {
        int coins = context.api().coins().getCoins(context.player().getUniqueId());
        return Integer.toString(coins);
    }
}
