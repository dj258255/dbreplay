#!/bin/sh
# explore --repeat 5를 세 번 돌려, 내놓는 상한의 범위가 서로 겹치는지 본다.
# 조건은 실험 12의 30초 녹화와 같다. 약 25분 걸린다. 도는 동안 이 장비에서 다른 일을 돌리지 않는다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp14_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
EXPLORE="--trace /exp/out/prod.trace.jsonl --url $URL --user root --snapshot /exp/out/snapshot.sql --alarm-metric active_sessions>=32 --start-speed 2 --max-speed 32"

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

$RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql </dev/null >/dev/null
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/record.txt
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/prod-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/prod-slow.log --out /exp/out/prod.trace.jsonl </dev/null | tee out/convert.txt
test "$(wc -l < out/prod.trace.jsonl)" -ge 100000
rm -f out/prod-slow.log

echo "===== --repeat 없이 한 번 (출력의 줄 구성이 전과 같은지 본다) ====="
$RUN /app/bin/dbreplay explore $EXPLORE --out /exp/out/evaluation-single.json </dev/null > out/explore-single.txt
grep -E '^상한' out/explore-single.txt

for i in 1 2 3; do
  echo "===== --repeat 5, ${i}번째 ====="
  start=$(date +%s)
  $RUN /app/bin/dbreplay explore $EXPLORE --repeat 5 --out "/exp/out/evaluation-repeat-$i.json" </dev/null > "out/explore-repeat-$i.txt"
  echo "($(( $(date +%s) - start ))초) $(grep -E '^상한' "out/explore-repeat-$i.txt")"
  grep -E '^  탐색마다|^수위|^  가장 높은' "out/explore-repeat-$i.txt"
done

echo "===== check ====="
$RUN /app/bin/dbreplay check --evaluation /exp/out/evaluation-repeat-1.json --url "$URL" --user root --alarm-metric "active_sessions>=32" </dev/null | tee out/check-repeat.txt
$RUN /app/bin/dbreplay check --evaluation /exp/out/evaluation-single.json --url "$URL" --user root --alarm-metric "active_sessions>=32" </dev/null | tee out/check-single.txt
echo "끝"
