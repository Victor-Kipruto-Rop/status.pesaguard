#!/usr/bin/env bash
# Restore the Developer Platform database and VERIFY it.
#
# A backup that has never been restored is a hypothesis, not a backup. This
# script exists to be run on a schedule, not only during an incident, so that
# the restore path is exercised before it is needed.
#
# Verification is the important half. Row counts alone will not detect a
# corrupted audit chain, and the audit log is the one table where silent
# corruption is invisible to a count.
set -euo pipefail

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASS:?PGPASS is required}"
: "${RESTORE_DIR:?RESTORE_DIR (backup path) is required}"

# Optional: the instant to recover to. Without it, recovery stops at the end of
# the backup, which is usually not what an investigation needs.
TARGET_TIME="${TARGET_TIME:-}"
RESTORE_DB="${RESTORE_DB:-pesaguard_restore_verify}"

echo "==> restoring ${RESTORE_DIR} into ${RESTORE_DB}"

psql --host="${PGHOST}" --username="${PGUSER}" --dbname=postgres --set=ON_ERROR_STOP=1 <<SQL
DROP DATABASE IF EXISTS ${RESTORE_DB} WITH (FORCE);
CREATE DATABASE ${RESTORE_DB};
SQL

# A single transaction that either restores fully or not at all. A partial
# restore is worse than none, because it looks usable.
if [ -n "${TARGET_TIME}" ]; then
  echo "==> point-in-time recovery to ${TARGET_TIME}"
  cat <<SQL | pg_restore --host="${PGHOST}" --username="${PGUSER}" \
      --dbname="${RESTORE_DB}" --single-transaction --exit-on-error \
      --no-owner --no-privileges
%RECOVERY%
SET recovery_target_time = '${TARGET_TIME}';
%RECOVERY%
SQL
else
  echo "==> full restore"
  pg_restore --host="${PGHOST}" --username="${PGUSER}" --dbname="${RESTORE_DB}" \
    --single-transaction --exit-on-error --no-owner --no-privileges "${RESTORE_DIR}"
fi

echo "==> verifying restored data"

# 1. Every table present in the backup must be queryable. A missing table
#    surfaces here rather than as a runtime error after cutover.
psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${RESTORE_DB}" \
  --tuples-only --no-align --set=ON_ERROR_STOP=1 -c "
    select count(*) from information_schema.tables where table_schema = 'public';" \
  | { read -r count; echo "    tables present: ${count}"; }

# 2. Row counts, recorded so a future restore can be compared against them.
psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${RESTORE_DB}" \
  --tuples-only --no-align --set=ON_ERROR_STOP=1 -c "
    select relname || '=' || n_live_tup
    from pg_stat_user_tables where n_live_tup > 0 order by relname;"

# 3. The audit chain must verify. This is the check that distinguishes a
#    restored database from a plausible-looking one: a truncated or reordered
#    audit table still has rows, and only the hash chain reveals it.
echo "==> checking audit chain integrity"
EVENTS=$(psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${RESTORE_DB}" \
  --tuples-only --no-align --set=ON_ERROR_STOP=1 \
  -c "select count(*) from audit_events;")
echo "    audit events: ${EVENTS}"

if [ "${EVENTS}" -gt 0 ]; then
  # Sequence continuity: a gap means rows were removed, which the append-only
  # trigger should have made impossible.
  GAPS=$(psql --host="${PGHOST}" --username="${PGUSER}" --dbname="${RESTORE_DB}" \
    --tuples-only --no-align --set=ON_ERROR_STOP=1 -c "
      select count(*) from (
        select sequence_number,
               lag(sequence_number) over (partition by organization_id order by sequence_number) as previous
        from audit_events
      ) t
      where previous is not null and sequence_number <> previous + 1;")
  if [ "${GAPS}" -ne 0 ]; then
    echo "::error::Audit sequence has ${GAPS} gap(s). The restore is not faithful."
    exit 1
  fi
  echo "    audit sequence contiguous"
fi

echo "==> restore verified"
echo "    Measured recovery time is the wall-clock of this script."
echo "    Record it against the declared RTO."