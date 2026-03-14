package hex.velocityfallback.model;

import java.net.InetSocketAddress;
import java.util.List;

public record FallbackConfig(
        String serverName,
        String host,
        int port,
        boolean redirectOnConnectFailure,
        boolean redirectOnEmptyReason,
        List<String> reasonKeywords
) {

    public InetSocketAddress address() {
        return InetSocketAddress.createUnresolved(host, port);
    }
}
