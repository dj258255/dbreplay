#!/bin/sh
# 데이터를 녹화 시작 시점으로 되돌린다.
# 덤프를 되붓고 나면 InnoDB 통계가 적재 도중의 값으로 남아 있을 수 있다. 실험 중에 sbtest1의 행 수가 46으로
# 남아 범위 조회가 풀 스캔으로 바뀌었다. 그래서 되돌린 뒤 통계를 다시 잡는다.
set -eu
cd "$(dirname "$0")"
docker compose exec -T mysql84 sh -c 'mysql -h127.0.0.1 -uroot -preplay sbtest < /tmp/snapshot.sql 2>/dev/null' </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay sbtest \
  -e "ANALYZE TABLE sbtest1, sbtest2, sbtest3, sbtest4" </dev/null >/dev/null 2>&1
