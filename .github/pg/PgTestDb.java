package peergos.server.tests;

import com.zaxxer.hikari.*;
import org.postgresql.ds.*;
import peergos.server.sql.*;
import peergos.server.util.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;

/** The Postgres the CI job runs on localhost, for tests that otherwise use SQLite */
public class PgTestDb {
    private static Connection direct;
    private static HikariDataSource pool;

    public static Args withPostgres(Args a) {
        return a.with("use-postgres", "true")
                .with("postgres.host", "localhost")
                .with("postgres.database", "peergostest")
                .with("postgres.username", "testuser")
                .with("postgres.password", "testpassword");
    }

    /** An empty schema, as one connection that ignores close; pool() then gives a new pool on it */
    public static synchronized Connection fresh() {
        try {
            if (pool != null)
                pool.close();
            pool = null;
            if (direct == null) {
                PGSimpleDataSource ds = new PGSimpleDataSource();
                ds.setServerNames(new String[] {"localhost"});
                ds.setDatabaseName("peergostest");
                ds.setUser("testuser");
                ds.setPassword("testpassword");
                direct = ds.getConnection();
            }
            direct.setAutoCommit(true);
            try (Statement s = direct.createStatement()) {
                s.execute("SET lock_timeout = '30s'");
                s.execute("DROP SCHEMA public CASCADE");
                s.execute("CREATE SCHEMA public");
            }
            return new Sqlite.UncloseableConnection(direct);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Pooled connections set up as Builder.getPostgresConnector sets up the server's */
    public static synchronized Supplier<Connection> pool() {
        if (pool == null) {
            Properties props = new Properties();
            props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
            props.setProperty("dataSource.serverName", "localhost");
            props.setProperty("dataSource.portNumber", "5432");
            props.setProperty("dataSource.user", "testuser");
            props.setProperty("dataSource.password", "testpassword");
            props.setProperty("dataSource.databaseName", "peergostest");
            HikariConfig config = new HikariConfig(props);
            config.setMaximumPoolSize(5);
            pool = new HikariDataSource(config);
        }
        HikariDataSource ds = pool;
        return () -> {
            try {
                return ds.getConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
