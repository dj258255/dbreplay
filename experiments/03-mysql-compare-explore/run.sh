#!/bin/sh
# 1) sysbench 부하를 녹화한다
# 2) 변경 전과 변경 후(테이블 압축)의 샌드박스에 1배속으로 틀고, 그때의 샌드박스 로그끼리 비교한다
# 3) 변경 전과 변경 후 각각에서 버티는 배속을 탐색한다
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp03_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/?sslMode=DISABLED&allowPublicKeyRetrieval=true"
SIZES="SELECT name, ROUND(file_size/1024/1024, 1) AS file_mb FROM information_schema.innodb_tablespaces WHERE name LIKE 'sbtest/%' ORDER BY name"

log_on() {
  # 켜진 채로 파일을 지우면 서버가 지워진 파일에 계속 쓴다. 먼저 끄고, 지우고, 다시 켠다.
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
  $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
  docker compose exec -T mysql84 test -f /var/lib/mysql/slow.log </dev/null
}
# $1: 꼬리표. 로그를 끄고 out/<꼬리표>-slow.log로 꺼내 out/<꼬리표>.trace.jsonl로 바꾼다
log_off() {
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > "out/$1-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$1-slow.log" --out "/exp/out/$1.trace.jsonl" </dev/null > "out/$1-convert.txt"
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

echo "== 녹화 =="
log_on
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/record-sysbench.txt
log_off prod
cat out/prod-convert.txt

echo "== 변경 전: 1배속 재생 =="
$RUN sh /exp/reset.sh </dev/null
$M -e "$SIZES" 2>/dev/null </dev/null | tee out/size-before.txt
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --speed 1 </dev/null | tee out/replay-before.txt
log_off before

echo "== 변경 후(테이블 압축): 1배속 재생 =="
$RUN sh /exp/reset-compressed.sh </dev/null
$M -e "$SIZES" 2>/dev/null </dev/null | tee out/size-after.txt
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --speed 1 </dev/null | tee out/replay-after.txt
log_off after

echo "== 비교: 원본 대 변경 전 재생 (재생이 원본과 같은가) =="
$RUN /app/bin/dbreplay compare --before /exp/out/prod.trace.jsonl --after /exp/out/before.trace.jsonl </dev/null | tee out/compare-prod-vs-before.txt
echo "== 비교: 변경 전 대 변경 후 =="
$RUN /app/bin/dbreplay compare --before /exp/out/before.trace.jsonl --after /exp/out/after.trace.jsonl </dev/null | tee out/compare-before-vs-after.txt

ALARMS='--alarm-active-sessions 32 --alarm-command'
echo "== 용량 탐색: 변경 전 =="
$RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user root \
  --reset-command "sh /exp/reset.sh" $ALARMS "sh /exp/alarm-slow.sh" --max-speed 32 \
  --out /exp/out/evaluation-before.json </dev/null | tee out/explore-before.txt
echo "== 용량 탐색: 변경 후(테이블 압축) =="
$RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user root \
  --reset-command "sh /exp/reset-compressed.sh" $ALARMS "sh /exp/alarm-slow.sh" --max-speed 32 </dev/null | tee out/explore-after.txt

echo "== 문장 단위 재생 (종 DB의 읽기를 다루는 방식) =="
$RUN sh /exp/reset.sh </dev/null
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --unit statement --speed 1 </dev/null | tee out/replay-statement.txt

echo "== 평가 유효성 확인 =="
$RUN /app/bin/dbreplay check --evaluation /exp/out/evaluation-before.json --url "$URL" --user root \
  $ALARMS "sh /exp/alarm-slow.sh" --trace /exp/out/prod.trace.jsonl </dev/null | tee out/check-valid.txt
$M -e "SET GLOBAL innodb_io_capacity = 2000" 2>/dev/null </dev/null
$RUN /app/bin/dbreplay check --evaluation /exp/out/evaluation-before.json --url "$URL" --user root \
  --alarm-active-sessions 64 --alarm-command "sh /exp/alarm-slow.sh" </dev/null | tee out/check-invalid.txt || true
