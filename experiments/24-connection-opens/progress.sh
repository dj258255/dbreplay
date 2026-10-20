#!/bin/sh
# 실험 24의 구성에서 읽기만 16배속으로 한 번 틀며 재생기의 매초 상태를 남긴다(#127). 결과는 out/progress/. 약 2분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp24_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
TRACE=${TRACE:-../22-workers/out/prod.trace.jsonl}
O=out/progress
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE/$O:/out -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"

test -s "$TRACE"
mkdir -p $O
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose down -v >/dev/null 2>&1 || true
trap 'docker compose down -v >/dev/null 2>&1 || true' EXIT
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB --threads=4 prepare >/dev/null </dev/null

cp "$TRACE" $O/trace.jsonl
# 4배속으로 한 번 데운 뒤 16배속으로 튼다. 상태는 16배속의 것만 남긴다
$RUN /app/bin/dbreplay replay --trace /out/trace.jsonl --url "$URL" --user root --only reads --speed 4 </dev/null >/dev/null
$RUN /app/bin/dbreplay replay --trace /out/trace.jsonl --url "$URL" --user root --only reads --speed 16 \
  --progress /out/progress.tsv </dev/null > $O/replay.txt
rm -f $O/trace.jsonl
cat $O/progress.tsv
