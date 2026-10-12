#!/bin/sh
# 같은 트래픽으로 탐색을 두 번 한다. 전체를 트랜잭션 단위로 트는 탐색과, 읽기만 문장 단위로 트는 탐색이다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp07_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
COMMON='--snapshot /exp/out/snapshot.sql --ready-command'
ALARMS='--alarm-metric active_sessions>=32 --alarm-command'

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/snapshot.sql

docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/record-sysbench.txt
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/prod-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/prod-slow.log --out /exp/out/prod.trace.jsonl </dev/null
test "$(wc -l < out/prod.trace.jsonl)" -ge 100000

for i in 1 2; do
  echo "== 전체를 튼 탐색 ($i) =="
  $RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user root $COMMON "sh /exp/ready.sh" \
    $ALARMS "sh /exp/alarm-slow.sh" --max-speed 64 </dev/null | tee "out/explore-all-$i.txt"
  echo "== 읽기만 튼 탐색 ($i) =="
  $RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user root $COMMON "sh /exp/ready.sh" \
    $ALARMS "sh /exp/alarm-slow.sh" --only reads --max-speed 64 </dev/null | tee "out/explore-reads-$i.txt"
done
