#!/bin/sh
# 진짜 MySQL에 읽기만 틀면서 늦은 문장이 그 자리에서 새 연결을 열었는지 센다(#119). 실험 22와 같은 구성과 트래픽 파일이다.
# 4배속을 한 번 데운 뒤 8, 16, 24배속을 한 바퀴로 REPEAT번(기본 3) 돈다. 약 3분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp24_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
TRACE=${TRACE:-../22-workers/out/prod.trace.jsonl}   # 실험 22의 run.sh가 녹화한 것
REPEAT=${REPEAT:-3}; O=${OUT:-out}
JAVAC=${JAVAC:-$(/usr/libexec/java_home -v 25)/bin/javac}
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"

test -s "$TRACE"
mkdir -p $O/classes
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
"$JAVAC" -d $O/classes -cp "$ROOT/build/install/dbreplay/lib/*" Bench.java
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
t0=$(date +%s)
docker run --rm --network $NET -v "$ROOT/build/install/dbreplay:/app:ro" -v "$HERE/$O:/out" -e DBREPLAY_PASSWORD=replay \
  dbreplay-exp-runner java -cp '/app/lib/*:/out/classes' Bench /out/trace.jsonl "$URL" root "$REPEAT" 4 8 16 24 </dev/null | tee $O/bench.txt
echo "$t0 $(date +%s)" > $O/times.txt
rm -f $O/trace.jsonl
python3 summarize.py | tee $O/summary.md
