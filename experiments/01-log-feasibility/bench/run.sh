#!/bin/sh
# 슬로우 로그를 끈 상태, 임계 0으로 켠 상태, 부가 필드까지 켠 상태를 번갈아 돌려 처리량 차이를 본다.
# 노트북 도커에서의 상대값이다. 절대값은 의미가 없다.
set -eu
cd "$(dirname "$0")/.."
ROUNDS=${ROUNDS:-3}
SECS=${SECS:-30}
THREADS=${THREADS:-8}
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network dbreplay-exp01_default dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"

docker build -q -t dbreplay-exp01-sysbench bench >/dev/null
$M -e "CREATE DATABASE IF NOT EXISTS sbtest;
       CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null
# caching_sha2_password는 평문 연결의 첫 로그인을 거절한다. 서버 쪽 캐시를 한 번 채워 둔다.
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1

$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null
$SB cleanup >/dev/null 2>&1 || true
$SB --threads=4 prepare >/dev/null

# 버퍼 풀이 데워지기 전의 첫 실행은 버린다.
$SB --threads="$THREADS" --time=20 --report-interval=0 run >/dev/null

echo "round mode tps p95_ms log_bytes"
for r in $(seq 1 "$ROUNDS"); do
  for mode in off on on_extra; do
    case $mode in
      off)      $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null ;;
      on)       $M -e "SET GLOBAL log_slow_extra=OFF; SET GLOBAL slow_query_log=ON" 2>/dev/null ;;
      on_extra) $M -e "SET GLOBAL log_slow_extra=ON;  SET GLOBAL slow_query_log=ON" 2>/dev/null ;;
    esac
    docker compose exec -T mysql84 sh -c ': > /var/lib/mysql/slow.log'
    $M -e "FLUSH SLOW LOGS" 2>/dev/null
    out=$($SB --threads="$THREADS" --time="$SECS" --report-interval=0 run)
    tps=$(echo "$out" | awk '/transactions:/ {gsub(/[()]/,""); print $3}')
    p95=$(echo "$out" | awk '/95th percentile:/ {print $3}')
    bytes=$(docker compose exec -T mysql84 sh -c 'wc -c < /var/lib/mysql/slow.log' | tr -d ' \r')
    echo "$r $mode $tps $p95 $bytes"
  done
done
$M -e "SET GLOBAL log_slow_extra=ON; SET GLOBAL slow_query_log=ON" 2>/dev/null
