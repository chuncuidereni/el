#!/bin/bash
# Run by the official MySQL entrypoint, ONLY for a new empty data volume.
# The reviewed historical DDL is applied once; never run it on an external DB.
set -e
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
for version in $(seq 1 11); do
    files=(/opt/el2ck-schema/archive/V"${version}"__*.sql)
    [ "${#files[@]}" -eq 1 ] && [ -f "${files[0]}" ] || { echo 'Missing bootstrap schema'; exit 1; }
    mysql --protocol=socket -uroot "$MYSQL_DATABASE" < "${files[0]}"
done
# V8-V11 already include the objects from 20261029_resume_and_progress.sql.
mysql --protocol=socket -uroot "$MYSQL_DATABASE" < /opt/el2ck-schema/20261029_review_hardening.sql
mysql --protocol=socket -uroot "$MYSQL_DATABASE" < /opt/el2ck-schema/20261110_sync_run_logical_delete.sql
# Mark success only after every statement succeeds. A partial initialization
# cannot pass the health check on a subsequent restart.
mysql --protocol=socket -uroot "$MYSQL_DATABASE" <<'SQL'
CREATE TABLE el_bundle_bootstrap (id INT PRIMARY KEY, ready INT NOT NULL);
INSERT INTO el_bundle_bootstrap VALUES (1, 1);
SQL
