#!/bin/sh
# 1) sysbench 부하를 슬로우 로그로 녹화한다  2) 트래픽 파일로 바꾼다
# 3) 데이터를 녹화 시작 시점으로 되돌리고 배속을 바꿔 가며 다시 튼다
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
RATE=${RATE:-200}        # 녹화할 때의 초당 트랜잭션 수. 서버 한계보다 낮게 잡아 간격이 있는 트래픽을 만든다
SECS=${SECS:-30}
THREADS=${THREADS:-8}
SPEEDS=${SPEEDS:-"1 2 4 8"}
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network dbreplay-exp02_default dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"

docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done

$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB cleanup >/dev/null 2>&1 </dev/null || true
$SB --threads=4 prepare >/dev/null </dev/null

# 녹화 시작 시점의 데이터. 재생 전마다 이것으로 되돌린다.
docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest > /tmp/snapshot.sql 2>/dev/null' </dev/null

# 파일을 지워 두면 로그를 켤 때 서버가 새로 만든다. 여기서 직접 만들면 소유자가 root가 되어 서버가 못 연다.
docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads="$THREADS" --time="$SECS" --rate="$RATE" --report-interval=0 run </dev/null > out/record-sysbench.txt
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/slow.log

cd "$ROOT"
./gradlew -q run --args="convert --in $HERE/out/slow.log --out $HERE/out/trace.jsonl" | tee "$HERE/out/convert.txt"
for speed in $SPEEDS; do
  "$HERE/reset.sh"
  echo "== ${speed}배속 =="
  DBREPLAY_PASSWORD=replay ./gradlew -q run --args="replay --trace $HERE/out/trace.jsonl \
    --url jdbc:mysql://127.0.0.1:33085/?sslMode=DISABLED&allowPublicKeyRetrieval=true \
    --user root --unit session --speed $speed --out $HERE/out/results-${speed}x.jsonl" | tee "$HERE/out/replay-${speed}x.txt"
done
