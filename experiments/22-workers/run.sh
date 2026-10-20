#!/bin/sh
# --workers로 나누면 읽기만의 탐색에서 재생기의 한계가 오르는지 잰다(#113). 실험 07과 같은 트래픽에서
# explore --only reads를 --workers 1과 4로 번갈아 PAIRS번씩 돌린다. 번갈아 돌리는 것은 장비가 도중에 느려져도(#99)
# 한 조건에만 몰리지 않게 하려는 것이다. 알람은 실험 07과 같은 active_sessions>=32이고 내장 알람만 쓴다. 약 30분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp22_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
PAIRS=${PAIRS:-5}; O=${OUT:-out}   # #115의 두 번째 실행은 OUT=out/shared-timer
# 탐색에 더 줄 옵션. #128에서 기본 시작 배속과 정밀도가 바뀌어, 앞의 실행과 같은 규칙으로 돌리려면
# EXTRA="--start-speed 1 --precision 0.1"을 준다(#144)
EXTRA=${EXTRA:-}

mkdir -p $O; rm -f $O/times.txt $O/w*-*.txt
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB --threads=4 prepare >/dev/null </dev/null

docker rm -f dbreplay-exp22-disk >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp22-disk postgres:16 bash -c 'while true; do a=$EPOCHREALTIME
  dd if=/dev/zero of=/tmp/probe bs=4k count=1 conv=fsync 2>/dev/null; b=$EPOCHREALTIME
  echo "${a%.*} $(( ${b/./} - ${a/./} ))"; sleep 1; done' >/dev/null
trap 'docker logs dbreplay-exp22-disk > $O/disk-probe.txt 2>&1 || true; docker rm -f dbreplay-exp22-disk >/dev/null 2>&1 || true' EXIT

# 녹화: 실험 07과 같다(sysbench oltp_read_write 초당 200, 30초)
docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > $O/record-sysbench.txt
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > $O/prod-slow.log
$RUN /app/bin/dbreplay convert --in /exp/$O/prod-slow.log --out /exp/$O/prod.trace.jsonl </dev/null
rm -f $O/prod-slow.log
test "$(wc -l < $O/prod.trace.jsonl)" -ge 100000

# 읽기만 틀므로 데이터가 바뀌지 않는다. 되돌리기를 건너뛴다(#109와 같다)
i=1
while [ "$i" -le "$PAIRS" ]; do
  for w in 1 4; do
    name="w$w-$i"; t0=$(date +%s)
    $RUN /app/bin/dbreplay explore --trace /exp/$O/prod.trace.jsonl --url "$URL" --user root --reset-command true \
      --only reads --workers "$w" --alarm-metric "active_sessions>=32" --max-speed 64 $EXTRA </dev/null > "$O/$name.txt" 2>&1 || true
    echo "$name $t0 $(date +%s)" >> $O/times.txt
    echo "--- $name: $(grep -E '^상한' "$O/$name.txt" || echo '값 없음')"
  done
  i=$((i + 1))
done
docker logs dbreplay-exp22-disk > $O/disk-probe.txt 2>&1
python3 summarize.py | tee $O/summary.md
echo "끝"
