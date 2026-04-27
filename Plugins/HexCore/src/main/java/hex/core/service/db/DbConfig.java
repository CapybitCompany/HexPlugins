package hex.core.service.db;

public final class DbConfig {
    public boolean enabled = true;
    public boolean required = false;
    public boolean debug = false;

    public String type = "";
    public String host = "";
    public int port = 3306;
    public String database = "";
    public String username = "";
    public String password = "";

    public String sqliteFile = "";

    public Pool pool = new Pool();
    public Options options = new Options();

    public String tablePrefix = "";

    public static final class Pool {
        public int maxSize = 10;
        public int minIdle = 2;
        public long timeoutMs = 5000;
        public long lifetimeMs = 1800000;
    }

    public static final class Options {
        public boolean useSSL = false;
        public String serverTimezone = "UTC";
    }
}