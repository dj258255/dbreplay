#!/bin/sh
# #54: sample이 낸 qps(MySQL)와 tps(PostgreSQL)가 부하 도구가 보고한 값과 맞는가. setup.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

echo "== MySQL: sysbench 초당 200 트랜잭션, 20초"
$RUN /app/bin/dbreplay sample --url "$MP" --user root --seconds 20 </dev/null > out/54-mysql-sample.txt &
sb mysql-primary oltp_read_write --threads=8 --time=20 --rate=200 --report-interval=0 run > out/54-mysql-sysbench.txt
wait
grep -E "queries:|transactions:" out/54-mysql-sysbench.txt
grep -E "^지표|^qps|^tps|^active|^slow" out/54-mysql-sample.txt

echo "== PostgreSQL: pgbench 초당 200 트랜잭션, 20초"
$RUN /app/bin/dbreplay sample --url "$PP" --user postgres --seconds 20 </dev/null > out/54-pg-sample.txt &
docker compose exec -T pg-primary pgbench -U postgres -n -c 8 -j 4 -T 20 -R 200 -M prepared shop </dev/null > out/54-pg-pgbench.txt 2>&1
wait
grep -E "^tps|number of transactions actually" out/54-pg-pgbench.txt
grep -E "^지표|^qps|^tps|^active|^slow" out/54-pg-sample.txt

echo "== 한가할 때 (표본기 자신의 조회가 빠지는가)"
$RUN /app/bin/dbreplay sample --url "$MP" --user root --seconds 5 </dev/null | grep -E "^qps|^slow_finished" || true
$RUN /app/bin/dbreplay sample --url "$PP" --user postgres --seconds 5 </dev/null | grep -E "^tps" || true

echo "== check --current-qps (check-57.sh가 남긴 평가 파일로)"
if [ -f out/57-evaluation.json ]; then
  $RUN /app/bin/dbreplay check --evaluation /exp/out/57-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
    --current-qps 1200 </dev/null | head -3
fi
