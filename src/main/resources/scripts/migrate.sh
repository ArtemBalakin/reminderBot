#!/usr/bin/env bash
set -euo pipefail

: "${BOT_DB_URL:?Missing BOT_DB_URL}"
: "${BOT_DB_USER:?Missing BOT_DB_USER}"
: "${BOT_DB_PASSWORD:?Missing BOT_DB_PASSWORD}"

SCHEMA="${BOT_DB_SCHEMA:-public}"
LOG_LEVEL="${LIQUIBASE_LOG_LEVEL:-info}"

exec liquibase \
  --url="${BOT_DB_URL}" \
  --username="${BOT_DB_USER}" \
  --password="${BOT_DB_PASSWORD}" \
  --default-schema-name="${SCHEMA}" \
  --search-path=/liquibase/changelog \
  --changelog-file=db.changelog-master.yaml \
  --log-level="${LOG_LEVEL}" \
  update
