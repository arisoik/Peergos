#!/usr/bin/env bash
# Run from a Peergos checkout: points the usage store tests at the CI job's Postgres, and lists in
# pg-classes.txt the test classes that now run on it.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
T=src/peergos/server/tests
cp "$here/PgTestDb.java" "$T/PgTestDb.java"
: > pg-classes.txt

# without IPFS, Main gives the transaction, bat and partition stores SQLite commands even on a Postgres
# connection, and Postgres refuses SQLite's "ADD COLUMN time" at start-up: use the configured dialect
M=src/peergos/server/Main.java
sed -i \
    -e 's/JdbcTransactionStore\.build(getDBConnector(args, "transactions-sql-file"), new SqliteCommands())/JdbcTransactionStore.build(getDBConnector(args, "transactions-sql-file"), getSqlCommands(args))/' \
    -e 's/SqliteCommands sqlCommands = new SqliteCommands();/SqlSupplier sqlCommands = getSqlCommands(args);/' \
    "$M"
if grep -n 'new SqliteCommands()' "$M"; then echo "NOT PATCHED: Main still hard-codes SQLite"; exit 1; fi

# store tests: an empty schema per test and the server's pool, instead of an in-memory SQLite
for c in JdbcUsageStoreTests CapUsageCacheTests; do
    f="$T/$c.java"
    [ -f "$f" ] || { echo "not in this tree: $c"; continue; }
    sed -i \
        -e 's/new Sqlite\.UncloseableConnection(Sqlite\.build(":memory:"))/PgTestDb.fresh()/' \
        -e 's/new JdbcUsageStore(() -> db, new SqliteCommands())/new JdbcUsageStore(PgTestDb.pool(), new PostgresCommands())/' \
        -e 's/new JdbcUsageStore(() -> pausing, new SqliteCommands())/new JdbcUsageStore(() -> pausing, new PostgresCommands())/' \
        "$f"
    if grep -q -E 'Sqlite\.build|SqliteCommands' "$f"; then
        echo "NOT PATCHED: $c still uses SQLite:"; grep -n -E 'Sqlite\.build|SqliteCommands' "$f"; exit 1
    fi
    echo "$c" >> pg-classes.txt
done

# server tests: the whole server on Postgres
for c in WriterQuotaTests fuzz/WriterQuotaFuzz BulkCommitWriteTests; do
    f="$T/$c.java"
    [ -f "$f" ] || { echo "not in this tree: $c"; continue; }
    sed -i \
        -e 's/UserTests\.useMemoryDbs(UserTests\.buildArgs())/PgTestDb.withPostgres(UserTests.useMemoryDbs(UserTests.buildArgs()))/' \
        -e 's/Args args = UserTests\.buildArgs()\.with("useIPFS", "false")/Args args = PgTestDb.withPostgres(UserTests.buildArgs()).with("useIPFS", "false")/' \
        "$f"
    grep -q 'PgTestDb.withPostgres' "$f" || { echo "NOT PATCHED: $c"; exit 1; }
    echo "$c" >> pg-classes.txt
done

# already on Postgres
echo slow/PostgresUserTests >> pg-classes.txt
cat pg-classes.txt
