#!/bin/sh
# 재생기 컨테이너 안에서 돈다. 데이터를 녹화 시작 시점으로 되돌리고 통계를 다시 잡는다.
set -eu
export PGPASSWORD=replay
psql -h pg16 -U postgres -d shop -q -v ON_ERROR_STOP=1 -f /exp/out/snapshot.sql >/dev/null
psql -h pg16 -U postgres -d shop -q -c "VACUUM ANALYZE" >/dev/null
