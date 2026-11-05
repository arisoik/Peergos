package peergos.server.tests;

import com.zaxxer.hikari.*;
import org.postgresql.ds.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

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

    /** A PKI node on SQLite, started as the tests start it, and a normal (non PKI) node that uses it, started with the
     *  daemon command and every database on Postgres. The normal node runs its p2p node in process only to reach the
     *  PKI. Returns the normal node, which the test then runs against.
     */
    public static ServerProcesses pkiAndPostgresNode(Args testArgs) {
        try {
            Args pkiArgs = UserTests.useMemoryDbs(testArgs)
                    .with("use-postgres", "false")
                    .with("useIPFS", "true")
                    .with("enable-gc", "false");
            ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
            PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
            Multihash pkiNodeId = pki.localApi.storage.id().get();
            String bootstrapList = Main.getLocalBootstrapAddress(pkiArgs.getInt("ipfs-swarm-port"), pkiNodeId).toString();

            Args node = withPostgres(UserTests.buildArgs())
                    .with("block-metadata-db-type", "postgres")
                    .with("metadb.postgres.host", "localhost")
                    .with("metadb.postgres.database", "peergostest")
                    .with("metadb.postgres.username", "testuser")
                    .with("metadb.postgres.password", "testpassword")
                    .with("useIPFS", "true")
                    .with("async-bootstrap", "false")
                    .with("enable-gc", testArgs.getArg("enable-gc", "true"))
                    .with("port", "" + TestPorts.getPort())
                    .with("pki-node-id", pkiNodeId.toString())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + TestPorts.getPort())
                    .with("ipfs-swarm-port", "" + TestPorts.getTcpAndUdpPort())
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, bootstrapList)
                    .with("proxy-target", Main.getLocalMultiAddress(TestPorts.getPort()).toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(TestPorts.getPort()).toString());
            ServerProcesses normal = Main.PEERGOS.main(node);
            System.out.println("Tests run on a normal node with Postgres; pki node " + pkiNodeId);
            return normal;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
