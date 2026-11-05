#!/usr/bin/env bash
# Run from a Peergos checkout: points the usage store tests at the CI job's Postgres, and lists in
# pg-classes.txt the test classes that now run on it.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
T=src/peergos/server/tests
cp "$here/PgTestDb.java" "$T/PgTestDb.java"
: > pg-classes.txt

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

# server tests: a normal (non PKI) node with every database on Postgres, using a PKI node on SQLite;
# Main is not changed
for c in WriterQuotaTests fuzz/WriterQuotaFuzz BulkCommitWriteTests slow/PostgresUserTests; do
    f="$T/$c.java"
    [ -f "$f" ] || { echo "not in this tree: $c"; continue; }
    sed -i -e 's/Main\.PKI_INIT\.main(args)\.localApi/PgTestDb.pkiAndPostgresNode(args).localApi/' "$f"
    if grep -q 'PKI_INIT' "$f" || ! grep -q 'PgTestDb.pkiAndPostgresNode' "$f"; then echo "NOT PATCHED: $c"; exit 1; fi
    echo "$c" >> pg-classes.txt
done

cat pg-classes.txt
