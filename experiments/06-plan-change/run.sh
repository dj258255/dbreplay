#!/bin/sh
# 통계가 맞는 샌드박스와 틀어진 샌드박스에 같은 트래픽을 틀어, compare가 읽은 행 수의 차이를 내는지 본다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp06_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"

log_on() {
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
  $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
}
log_off() {
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > "out/$1-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$1-slow.log" --out "/exp/out/$1.trace.jsonl" </dev/null >/dev/null
  lines=$(wc -l < "out/$1.trace.jsonl")
  if [ "$lines" -lt 30000 ]; then echo "out/$1.trace.jsonl이 ${lines}줄뿐이다" >&2; exit 1; fi
  rm -f "out/$1-slow.log"
}

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/snapshot.sql

log_on
$SB --threads=8 --time=20 --rate=100 --report-interval=0 run </dev/null > out/record-sysbench.txt
log_off prod

echo "== 통계가 맞는 샌드박스 =="
$RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql </dev/null
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --speed 1 </dev/null | grep -E "문장 |오류"
log_off before

echo "== 통계가 틀어진 샌드박스 =="
$RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql --ready-command "sh /exp/stale-stats.sh" </dev/null
$M sbtest -e "EXPLAIN SELECT c FROM sbtest1 WHERE id BETWEEN 50000 AND 50099\G" 2>/dev/null </dev/null | grep -E "type:|rows:" | tee out/explain-stale.txt
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --speed 1 </dev/null | grep -E "문장 |오류"
log_off after

$RUN /app/bin/dbreplay compare --before /exp/out/before.trace.jsonl --after /exp/out/after.trace.jsonl </dev/null | tee out/compare.txt
