#!/bin/sh
# 되돌린 직후에 재생하는 것과, 서버가 조용해질 때까지 기다린 뒤 재생하는 것을 비교한다.
# 재는 것은 재생의 COMMIT 평균이 원본의 COMMIT 평균의 몇 배인가다. MySQL에서 각각 REPS번.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
REPS=${REPS:-3}
NET=dbreplay-exp05_default
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
  if [ "$lines" -lt 100000 ]; then echo "out/$1.trace.jsonl이 ${lines}줄뿐이다" >&2; exit 1; fi
  rm -f "out/$1-slow.log"
}
# 원본 대 재생 비교에서 전체 평균과 COMMIT 평균을 뽑는다
row() {
  $RUN /app/bin/dbreplay compare --before /exp/out/prod.trace.jsonl --after "/exp/out/$1.trace.jsonl" </dev/null > "out/$1-compare.txt"
  awk -v tag="$1" -v reset="$2" '
    /실행 시간 평균/ { gsub(/ms|,/, ""); all_b=$4; all_a=$6 }
    $NF=="COMMIT" && !c { gsub(/ms/, ""); cb=$2; ca=$3; c=1 }
    END { printf "%-12s %8.3f %8.3f %7.2f %8.3f %8.3f %7.2f  %s\n", tag, all_b, all_a, all_a/all_b, cb, ca, ca/cb, reset }' "out/$1-compare.txt"
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

# 녹화하기 전에도 같은 방식으로 되돌리고 기다린다. 원본이 조용한 서버에서 녹화된 것이어야 비교가 된다
$RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql --settle-seconds 120 </dev/null | tee out/reset-before-record.txt
log_on
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/record-sysbench.txt
log_off prod

echo "조건         전체_원본 전체_재생    배율 커밋_원본 커밋_재생    배율  되돌리기" | tee out/result.txt
for i in $(seq 1 "$REPS"); do
  for mode in nowait wait; do
    case $mode in nowait) settle=0 ;; wait) settle=120 ;; esac
    reset=$($RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot.sql --settle-seconds $settle </dev/null)
    log_on
    $RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user root --speed 1 </dev/null > "out/$mode-$i-replay.txt"
    log_off "$mode-$i"
    row "$mode-$i" "$reset" | tee -a out/result.txt
    rm -f "out/$mode-$i.trace.jsonl"
  done
done

echo "== PostgreSQL에서 되돌리기가 도는지 ==" | tee -a out/result.txt
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
docker compose exec -T pg16 pg_dump -U postgres --clean --if-exists shop </dev/null > out/pg-snapshot.sql
$P -c "UPDATE pgbench_accounts SET abalance = 7 WHERE aid <= 1000" </dev/null >/dev/null
echo "되돌리기 전 바뀐 행: $($P -c "SELECT count(*) FROM pgbench_accounts WHERE abalance <> 0" </dev/null)" | tee -a out/result.txt
$RUN /app/bin/dbreplay reset --url "jdbc:postgresql://pg16:5432/shop" --user postgres --snapshot /exp/out/pg-snapshot.sql </dev/null | tee -a out/result.txt
echo "되돌린 뒤 바뀐 행: $($P -c "SELECT count(*) FROM pgbench_accounts WHERE abalance <> 0" </dev/null), 전체 행: $($P -c "SELECT count(*) FROM pgbench_accounts" </dev/null)" | tee -a out/result.txt
