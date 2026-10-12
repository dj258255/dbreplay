#!/bin/sh
# 같은 절차를 되풀이했을 때 상한이 얼마나 흔들리는지 잰다.
# 녹화 길이(30초, 5분) x 되돌린 뒤 대기(없음, 있음)의 네 조합을 차례로 한 번씩 도는 것을 5번 되풀이한다.
# 약 2시간 반 걸린다. 도는 동안 이 장비에서 다른 일을 돌리지 않는다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp12_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
REPEAT=${REPEAT:-5}

# $1 꼬리표, $2 녹화 초, $3 최소 줄 수. 되돌린 상태에서 녹화한다
record() {
  $RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql </dev/null >/dev/null
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
  $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
  $SB --threads=8 --time="$2" --rate=200 --report-interval=0 run </dev/null > "out/record-$1.txt"
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > "out/$1-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$1-slow.log" --out "/exp/out/prod-$1.trace.jsonl" </dev/null | tee "out/convert-$1.txt"
  test "$(wc -l < "out/prod-$1.trace.jsonl")" -ge "$3"
  rm -f "out/$1-slow.log"
}

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB cleanup >/dev/null 2>&1 </dev/null || true
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/snapshot.sql

record 30s 30 100000
record 5m 300 1000000

i=1
while [ "$i" -le "$REPEAT" ]; do
  for length in 30s 5m; do
    for wait in nowait wait; do
      settle=60; [ "$wait" = nowait ] && settle=0
      start=$(date +%s)
      $RUN /app/bin/dbreplay explore --trace "/exp/out/prod-$length.trace.jsonl" --url "$URL" --user root \
        --snapshot /exp/out/snapshot.sql --settle-seconds "$settle" --alarm-metric "active_sessions>=32" \
        --start-speed 2 --max-speed 32 </dev/null > "out/explore-$length-$wait-$i.txt" 2>&1 || true
      echo "--- $length $wait $i ($(( $(date +%s) - start ))초): $(grep -E '^상한' "out/explore-$length-$wait-$i.txt" || echo '값 없음')"
    done
  done
  i=$((i + 1))
done
python3 summarize.py | tee out/summary.md
echo "끝"
