#!/bin/sh
# 내장 알람의 연속 횟수(3 = 0.6초, 25 = 5초)를 바꾸면 상한의 흔들림이 줄어드는지 잰다. 이슈 #75.
# 5분 녹화 하나로 연속 3과 25를 번갈아 explore --repeat 5를 두 번씩 돈다(탐색 20번). 약 2시간 반 걸린다.
# 도는 동안 이 장비에서 빌드나 다른 실험을 돌리지 않는다. 30초마다 1분 부하 평균을 out/load.txt에 남긴다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp17_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
# 첫 실행과 같은 코드로 다시 돌리려고 06:57의 설치본을 app-0657에 떠 두었다. APP로 고른다
APP=${APP:-$ROOT/build/install/dbreplay}
RUN="docker run --rm --network $NET -v $APP:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
ORDER=${ORDER:-"3 25 3 25"}
# 중간에서 이어 돌릴 때 첫 차례 번호. 첫 실행이 스크립트의 오류로 멈춰 2부터 이어 돌렸다(README)
n=${N0:-1}

# 이어 돌릴 때는 빌드하지 않는다. 작업 트리의 코드가 바뀌었으면 첫 실행과 다른 코드로 돌게 된다
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
# 스냅샷이 있으면 그대로 쓴다. 다시 뜨면 sysbench가 데이터를 새로 만든다
if [ ! -s out/snapshot.sql ]; then
  $SB cleanup >/dev/null 2>&1 </dev/null || true
  $SB --threads=4 prepare >/dev/null </dev/null
  docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/snapshot.sql
fi

# 실험 12의 5분 녹화와 같은 절차. 되돌린 상태에서 sysbench 초당 200 트랜잭션을 300초 녹화한다
if [ ! -s out/prod-5m.trace.jsonl ]; then
  $RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql </dev/null >/dev/null
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
  $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
  $SB --threads=8 --time=300 --rate=200 --report-interval=0 run </dev/null > out/record-5m.txt
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/5m-slow.log
  $RUN /app/bin/dbreplay convert --in /exp/out/5m-slow.log --out /exp/out/prod-5m.trace.jsonl </dev/null | tee out/convert-5m.txt
  test "$(wc -l < out/prod-5m.trace.jsonl)" -ge 1000000
  rm -f out/5m-slow.log
fi

# 부하 평균 기록. 이 스크립트가 끝나면 함께 멈춘다
( while :; do echo "$(date +%H:%M:%S) $(sysctl -n vm.loadavg | awk '{print $2}')" >> out/load.txt; sleep 30; done ) &
LOADER=$!
# 디스크 탐침(실험 21, #99). 별도 컨테이너가 4KB를 fsync로 쓰는 시간(µs)을 1초마다 적는다. 무너진 실행을 가리는 데 쓴다
docker rm -f dbreplay-exp17-disk >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp17-disk postgres:16 bash -c 'while true; do a=$EPOCHREALTIME
  dd if=/dev/zero of=/tmp/probe bs=4k count=1 conv=fsync 2>/dev/null; b=$EPOCHREALTIME
  echo "${a%.*} $(( ${b/./} - ${a/./} ))"; sleep 1; done' >/dev/null
trap 'kill $LOADER 2>/dev/null || true; docker logs dbreplay-exp17-disk > out/disk-probe.txt 2>&1 || true; docker rm -f dbreplay-exp17-disk >/dev/null 2>&1 || true' EXIT

# 실행 앞뒤의 상태. 녹화와 같은 sysbench 부하(초당 200)를 20초 걸어 평균 지연(ms)을 본다. 다시 돌릴 때 더했다(#75의 12:30 코멘트)
probe() { $SB --threads=8 --time=20 --rate=200 --report-interval=0 run </dev/null | awk '/avg:/ {print $2; exit}'; }
for sustain in $ORDER; do
  echo "probe-before ${sustain} ${n} $(probe)" >> out/probe.txt
  start=$(date +%s); from=$(date +%H:%M:%S)
  $RUN /app/bin/dbreplay explore --trace /exp/out/prod-5m.trace.jsonl --url "$URL" --user root \
    --snapshot /exp/out/snapshot.sql --alarm-metric "active_sessions>=32" --alarm-sustain "$sustain" \
    --start-speed 2 --max-speed 32 --repeat 5 </dev/null > "out/explore-s${sustain}-${n}.txt" 2>&1 || true
  echo "# $from $(date +%H:%M:%S)" >> "out/explore-s${sustain}-${n}.txt"
  echo "--- 연속 ${sustain}, ${n}번째 ($from~$(date +%H:%M:%S), $(( $(date +%s) - start ))초): $(grep -E '^상한: 최소' "out/explore-s${sustain}-${n}.txt" || echo '값 없음')"
  echo "probe-after ${sustain} ${n} $(probe)" >> out/probe.txt
  n=$((n + 1))
done
docker logs dbreplay-exp17-disk > out/disk-probe.txt 2>&1
python3 summarize.py | tee out/summary.md
echo "끝"
