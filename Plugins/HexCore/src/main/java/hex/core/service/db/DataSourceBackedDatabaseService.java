package hex.core.service.db;

import hex.core.api.db.DatabaseService;

import javax.sql.DataSource;

public interface DataSourceBackedDatabaseService extends DatabaseService {
    DataSource dataSource();
}

