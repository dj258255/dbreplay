#!/bin/sh
# 종 DB. 처음 뜰 때 주 DB에서 베이스 백업을 받아 스트리밍 복제로 붙는다. -R이 standby.signal과 접속 정보를 적는다.
set -eu
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  until pg_isready -h pg-primary -U postgres -q; do sleep 1; done
  pg_basebackup -h pg-primary -U postgres -D "$PGDATA" -R -X stream
  chmod 700 "$PGDATA"
fi
exec postgres -c logging_collector=on -c log_destination=csvlog -c log_directory=log -c log_filename=pg.log \
  -c log_rotation_age=0 -c log_rotation_size=0 -c log_timezone=UTC -c log_disconnections=on -c max_connections=300
