package hex.core.placeholder;

import hex.core.api.HexApi;
import org.bukkit.entity.Player;

public record HexPlaceholderContext(
        HexApi api,
        Player player,
        String identifier
) {
}

