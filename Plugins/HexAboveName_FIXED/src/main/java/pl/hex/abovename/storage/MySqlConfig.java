package pl.hex.abovename.storage;

/**
 * Plain-record snapshot of {@code storage.mysql.*} from config.yml.
 * Built by the plugin from the YAML config; immutable downstream.
 */
public record MySqlConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        String table,
        boolean useSsl,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long maxLifetimeMs
) {
    public String jdbcUrl() {
        StringBuilder sb = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port)
                .append('/').append(database)
                .append("?useUnicode=true&characterEncoding=utf8")
                .append("&useSSL=").append(useSsl)
                .append("&allowPublicKeyRetrieval=").append(!useSsl);
        return sb.toString();
    }
}
