#!/usr/bin/env bash
# PostgreSQL base backup for the Developer Platform.
#
# Run daily. Combined with WAL archiving (see postgres.conf) this is what makes
# point-in-time recovery possible; a base backup on its own is only good to the
# moment it was taken.
#
# Retention is deliberately longer than the audit table's, because a restore
# performed for an investigation may need to reach back further than the
# operational backup window.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${BACKUP_ROOT:=/var/backups/pesaguard}"
: "${PGPASS:?PGPASS is required}"

RETENTION_DAYS="${RETENTION_DAYS:-30}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${BACKUP_ROOT}/base/${STAMP}"

mkdir -p "${DEST}"

echo "==> base backup starting: ${DEST}"

# --format=custom produces a compressed, restorable archive. plain SQL cannot be
# restored selectively, and cannot be used for point-in-time recovery at all.
pg_basebackup \
  --host="${PGHOST}" \
  --username="${PGUSER}" \
  --dbname="${PGDATABASE}" \
  --format=custom \
  --gzip \
  --wal-method=stream \
  --checkpoint=fast \
  --label="pesaguard-${STAMP}" \
  --pgdata="${DEST}"

# A manifest is what makes a backup auditable months later: without it, nobody
# can say whether a given archive is complete or what it contains.
cat > "${DEST}/manifest.txt" <<EOF
backup_id=${STAMP}
database=${PGDATABASE}
host=${PGHOST}
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
postgres_version=$(psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${PGDATABASE}" --tuples-only --no-align -c 'SHOW server_version;')
table_counts:
$(psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${PGDATABASE}" --tuples-only --no-align -c "
  select relname || '=' || n_live_tup
  from pg_stat_user_tables
  where n_live_tup > 0
  order by relname;")
EOF

echo "==> backup complete: ${DEST}"

# Retention. Pruning is explicit and logged rather than delegated to a timer
# nobody notices failing.
find "${BACKUP_ROOT}/base" -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print -exec rm -rf {} + 2>/dev/null || true

echo "==> retention applied (${RETENTION_DAYS} days)"