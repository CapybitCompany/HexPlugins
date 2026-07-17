package hex.vishopbroadcast.proxy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hex.vishopbroadcast.proxy.config.ProxySettings;

public final class ProxyDatabase implements AutoCloseable {
    private final HikariDataSource dataSource;

    public ProxyDatabase(ProxySettings.Database settings) {
        boolean mariaDb = settings.type().equalsIgnoreCase("mariadb");
        String driver = mariaDb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
        String scheme = mariaDb ? "mariadb" : "mysql";

        HikariConfig config = new HikariConfig();
        config.setPoolName("VishopBroadcastProxy");
        config.setDriverClassName(driver);
        config.setJdbcUrl("jdbc:" + scheme + "://" + settings.host() + ":" + settings.port() + "/" + settings.name()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000L);
        this.dataSource = new HikariDataSource(config);
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
