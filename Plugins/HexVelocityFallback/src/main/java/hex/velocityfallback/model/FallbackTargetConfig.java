package hex.velocityfallback.model;

import java.net.InetSocketAddress;

public record FallbackTargetConfig(String serverName, String host, Integer port) {

    public boolean hasAddress() {
        return host != null && !host.isBlank() && port != null;
    }

    public InetSocketAddress address() {
        if (!hasAddress()) {
            throw new IllegalStateException("Target server address is not fully configured.");
        }
        return InetSocketAddress.createUnresolved(host, port);
    }
}
