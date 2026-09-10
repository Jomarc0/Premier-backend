#!/usr/bin/env bash
set -euo pipefail
# This gate only targets the ephemeral CI PostgreSQL service, never DB_URL.
common=(
  -Dspring.datasource.username=premier
  -Dspring.datasource.password=ci-synthetic-only
  -Dspring.datasource.driver-class-name=org.postgresql.Driver
  -Dspring.datasource.hikari.data-source-properties.ssl=false
  -Dspring.datasource.hikari.data-source-properties.sslmode=disable
  -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
)
./mvnw --batch-mode --no-transfer-progress clean verify "${common[@]}" \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:5432/premier_test | tee ci-postgresql.log
python3 - <<'PY'
from pathlib import Path
assert 'org.postgresql.jdbc.PgConnection' in Path('ci-postgresql.log').read_text(), 'No PostgreSQL evidence'
PY
