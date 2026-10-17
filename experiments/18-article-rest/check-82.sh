#!/bin/sh
# #82 운영 QPS를 도구가 잰다. (1) MySQL: sysbench를 정한 속도로 걸고 check --measure-url이 잰 값을 sysbench의 값과 맞춰 본다
# (2) PostgreSQL: pg_stat_statements로 qps가 나오는가, --server-log로 0.2초짜리 문장 열 개를 세는가. check-80.sh의 평가 파일을 쓴다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
echo "== MySQL: sysbench 초당 100 트랜잭션(oltp_read_write, 트랜잭션마다 문장 18개 안팎)을 거는 동안 20초 잰다"
sb mysql-primary oltp_read_write --threads=4 --time=30 --rate=100 --report-interval=0 run > out/82-sysbench.txt &
sleep 3
$RUN /app/bin/dbreplay check --evaluation /exp/out/80-eval.json --url "$MP" --user root --measure-url "$MP" --measure-seconds 20 </dev/null \
  | grep -E "운영 DB|수위" || true
wait
grep -E "queries:|transactions:" out/82-sysbench.txt
echo "== PostgreSQL: pgbench 초당 50 트랜잭션을 거는 동안 15초 뜬다. 그사이 0.2초짜리 문장 열 개"
pp -c "ALTER SYSTEM SET log_min_duration_statement = 100" >/dev/null; pp -c "SELECT pg_reload_conf()" >/dev/null
docker compose exec -T pg-primary pgbench -U postgres -c 4 -T 20 -R 50 shop </dev/null > out/82-pgbench.txt 2>&1 &
sleep 2
( sleep 3; i=0; while [ $i -lt 10 ]; do pp -c "SELECT pg_sleep(0.2)" >/dev/null; i=$((i+1)); done ) &
$RUN /app/bin/dbreplay sample --url "$PP" --user postgres --seconds 15 --server-log /exp/pglog/pg.csv </dev/null | grep -E "qps|tps|slow_finished"
wait
grep -E "tps = |number of transactions actually processed" out/82-pgbench.txt
pp -c "ALTER SYSTEM RESET log_min_duration_statement" >/dev/null; pp -c "SELECT pg_reload_conf()" >/dev/null
echo "== MySQL에 --server-log를 주면 거절하는가"
$RUN /app/bin/dbreplay sample --url "$MP" --user root --seconds 1 --server-log /exp/pglog/pg.csv </dev/null 2>&1 | grep -E "Exception|server-log" | head -2
