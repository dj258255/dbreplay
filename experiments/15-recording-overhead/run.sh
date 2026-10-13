#!/bin/sh
# 모든 문장을 서버 로그로 남길 때 처리량과 지연이 얼마나 달라지는지 잰다.
# 속도 제한 없이 8개 연결로 30초씩, 로그 끔과 켬을 번갈아 5번. MySQL과 PostgreSQL. 약 12분 걸린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
NET=dbreplay-exp15_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
CSV=/var/lib/postgresql/data/log/pg.csv
RUNS="1 2 3 4 5"

docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done

echo "===== MySQL 8.4 ====="
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB cleanup >/dev/null 2>&1 </dev/null || true
$SB --threads=4 prepare >/dev/null </dev/null
$M -e "SELECT @@long_query_time, @@log_slow_extra, @@log_output" 2>/dev/null </dev/null
# 데운다. 첫 실행이 버퍼 풀을 채우는 값을 한쪽에 싣지 않게 한다
$SB --threads=8 --time=20 --report-interval=0 run </dev/null >/dev/null
for i in $RUNS; do
  for mode in off on; do
    $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
    docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
    [ "$mode" = on ] && $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
    $SB --threads=8 --time=30 --report-interval=0 --percentile=95 run </dev/null > "out/mysql-$mode-$i.txt"
    $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
    bytes=$(docker compose exec -T mysql84 sh -c 'stat -c %s /var/lib/mysql/slow.log 2>/dev/null || echo 0' </dev/null | tr -d '\r')
    tps=$(awk '/transactions:/ {gsub(/[()]/, "", $3); print $3}' "out/mysql-$mode-$i.txt")
    qps=$(awk '/queries:/ {gsub(/[()]/, "", $3); print $3}' "out/mysql-$mode-$i.txt")
    p95=$(awk '/95th percentile:/ {print $3}' "out/mysql-$mode-$i.txt")
    echo "mysql $mode $i tps=$tps qps=$qps p95_ms=$p95 log_bytes=$bytes"
  done
done

echo "===== PostgreSQL 16 ====="
docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
docker compose exec -T pg16 pgbench -U postgres -n -c 8 -j 4 -T 20 -M prepared shop </dev/null >/dev/null 2>&1
for i in $RUNS; do
  for mode in off on; do
    docker compose exec -T -u postgres pg16 sh -c ": > $CSV" </dev/null
    value=-1; [ "$mode" = on ] && value=0
    $P -c "ALTER SYSTEM SET log_min_duration_statement = $value" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
    sleep 1
    docker compose exec -T pg16 pgbench -U postgres -n -c 8 -j 4 -T 30 -M prepared shop </dev/null > "out/pg-$mode-$i.txt" 2>&1
    $P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
    sleep 1
    bytes=$(docker compose exec -T pg16 sh -c "stat -c %s $CSV" </dev/null | tr -d '\r')
    tps=$(awk '/^tps = / {print $3}' "out/pg-$mode-$i.txt")
    lat=$(awk '/^latency average = / {print $4}' "out/pg-$mode-$i.txt")
    echo "pg $mode $i tps=$tps latency_avg_ms=$lat log_bytes=$bytes"
  done
done
echo "끝"
