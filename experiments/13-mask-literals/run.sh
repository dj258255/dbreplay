#!/bin/sh
# 같은 녹화를 세 수준(none, strings, all)으로 가려 변환하고, 수준마다
#   1) 1배속으로 3번 틀어 그때의 서버 로그를 가리지 않은 재생의 로그와 비교한다
#   2) 3번 탐색해 상한을 본다
# 가리지 않은 재생끼리도 비교한다. 같은 것을 두 번 틀었을 때의 차이가 기준선이다.
# MySQL의 all 수준은 재생이 끝나지 않는다(out/first-attempt). 2분의 한도를 두고 한 번만 튼다.
# 인자로 mysql이나 postgres를 주면 그 기종만 돈다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
ONLY=${1:-both}
NET=dbreplay-exp13_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
ALL_LEVELS="none strings all"
RUNS="1 2 3"

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true

# $1 기종 꼬리표(mysql|pg), $2 URL, $3 계정, $4 덤프, $5 잴 수준들. log_on과 log_off는 기종별로 정의해 둔다
measure() {
  tag=$1; url=$2; user=$3; snap=$4; LEVELS=$5
  echo "===== $tag: 1배속 ====="
  for i in $RUNS; do
    for level in $LEVELS; do
      $RUN /app/bin/dbreplay reset --url "$url" --user "$user" --snapshot "/exp/out/$snap" </dev/null >/dev/null
      log_on
      $RUN /app/bin/dbreplay replay --trace "/exp/out/$tag-prod-$level.trace.jsonl" --url "$url" --user "$user" --speed 1 </dev/null > "out/$tag-replay-$level-$i.txt"
      log_off "$tag-$level-$i"
      echo "--- $tag $level $i: $(grep -E '^오류' "out/$tag-replay-$level-$i.txt")"
    done
    for level in $LEVELS; do
      [ "$level" = none ] && continue
      $RUN /app/bin/dbreplay compare --before "/exp/out/$tag-none-$i.trace.jsonl" --after "/exp/out/$tag-$level-$i.trace.jsonl" --top 5 --rows 60 </dev/null > "out/$tag-compare-$level-$i.txt"
    done
  done
  # 기준선: 가리지 않은 재생끼리
  $RUN /app/bin/dbreplay compare --before "/exp/out/$tag-none-1.trace.jsonl" --after "/exp/out/$tag-none-2.trace.jsonl" --top 5 --rows 60 </dev/null > "out/$tag-compare-baseline-1.txt"
  $RUN /app/bin/dbreplay compare --before "/exp/out/$tag-none-2.trace.jsonl" --after "/exp/out/$tag-none-3.trace.jsonl" --top 5 --rows 60 </dev/null > "out/$tag-compare-baseline-2.txt"
  $RUN /app/bin/dbreplay compare --before "/exp/out/$tag-none-1.trace.jsonl" --after "/exp/out/$tag-none-3.trace.jsonl" --top 5 --rows 60 </dev/null > "out/$tag-compare-baseline-3.txt"

  echo "===== $tag: 상한 ====="
  for i in $RUNS; do
    for level in $LEVELS; do
      $RUN /app/bin/dbreplay explore --trace "/exp/out/$tag-prod-$level.trace.jsonl" --url "$url" --user "$user" \
        --snapshot "/exp/out/$snap" --alarm-metric "active_sessions>=32" --max-speed 32 </dev/null > "out/$tag-explore-$level-$i.txt"
      echo "--- $tag 상한 $level $i: $(grep -E '^상한' "out/$tag-explore-$level-$i.txt")"
    done
  done
}

if [ "$ONLY" != postgres ]; then
  M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
  URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
  log_on() {
    $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
    docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
    $M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
    docker compose exec -T mysql84 test -f /var/lib/mysql/slow.log </dev/null
  }
  # $1 꼬리표. 서버 로그를 꺼내 가리지 않고 변환한다
  log_off() {
    $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
    docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > "out/$1-slow.log"
    $RUN /app/bin/dbreplay convert --in "/exp/out/$1-slow.log" --out "/exp/out/$1.trace.jsonl" </dev/null >/dev/null
    test "$(wc -l < "out/$1.trace.jsonl")" -ge 100000
    rm -f "out/$1-slow.log"
  }
  until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
  $M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
  docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
  SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
  $SB cleanup >/dev/null 2>&1 </dev/null || true
  $SB --threads=4 prepare >/dev/null </dev/null
  docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/mysql-snapshot.sql
  echo "===== mysql: 녹화와 세 수준의 변환 ====="
  log_on
  $SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/mysql-record.txt
  $M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
  docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/mysql-prod-slow.log
  for level in $ALL_LEVELS; do
    $RUN /app/bin/dbreplay convert --in /exp/out/mysql-prod-slow.log --out "/exp/out/mysql-prod-$level.trace.jsonl" --mask "$level" </dev/null | tee "out/mysql-convert-$level.txt"
  done
  rm -f out/mysql-prod-slow.log
  # 같은 문장이 수준마다 어떻게 보이는지 남긴다. 값은 sysbench가 만든 것이다
  for level in $ALL_LEVELS; do
    echo "[$level]"; grep -m1 '"sql":"UPDATE sbtest[0-9] SET c=' "out/mysql-prod-$level.trace.jsonl" | cut -c1-260
    grep -m1 '"sql":"SELECT c FROM sbtest[0-9] WHERE id BETWEEN' "out/mysql-prod-$level.trace.jsonl" | cut -c1-200
  done > out/mysql-samples.txt
  measure mysql "$URL" root mysql-snapshot.sql "none strings"

  echo "===== mysql: all 수준을 2분의 한도로 한 번 ====="
  $RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/mysql-snapshot.sql </dev/null >/dev/null
  if $RUN timeout 120 /app/bin/dbreplay replay --trace /exp/out/mysql-prod-all.trace.jsonl --url "$URL" --user root --speed 1 </dev/null > out/mysql-replay-all-1.txt 2>&1; then
    echo "한도 안에 끝났다: $(grep -E '^오류' out/mysql-replay-all-1.txt)"
  else
    echo "2분 안에 끝나지 않았다 (원본은 30초 분량이다)"
  fi
  $M -e "SELECT COUNT(*) AS running, MAX(TIME) AS longest_seconds FROM information_schema.PROCESSLIST WHERE COMMAND <> 'Sleep' AND ID <> CONNECTION_ID();
         SELECT LEFT(INFO, 70) AS statement, COUNT(*) AS n FROM information_schema.PROCESSLIST WHERE COMMAND <> 'Sleep' AND ID <> CONNECTION_ID() GROUP BY LEFT(INFO, 70) ORDER BY n DESC LIMIT 3" 2>/dev/null </dev/null | tee out/mysql-all-state.txt
  # 재생기가 죽어도 서버는 하던 조회를 계속한다. 다음 기종에 섞이지 않게 서버를 내린다
  docker compose stop mysql84 >/dev/null 2>&1
fi

if [ "$ONLY" != mysql ]; then
  P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
  URL="jdbc:postgresql://pg16:5432/shop?preferQueryMode=simple"
  CSV=/var/lib/postgresql/data/log/pg.csv
  log_on() {
    docker compose exec -T -u postgres pg16 sh -c ": > $CSV" </dev/null
    $P -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
    sleep 1
  }
  log_off() {
    $P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
    sleep 1
    docker compose exec -T pg16 cat $CSV </dev/null > "out/$1.csv"
    $RUN /app/bin/dbreplay convert --from postgres-csv --in "/exp/out/$1.csv" --out "/exp/out/$1.trace.jsonl" </dev/null >/dev/null
    test "$(wc -l < "out/$1.trace.jsonl")" -ge 30000
    rm -f "out/$1.csv"
  }
  until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
  docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
  docker compose exec -T pg16 pg_dump -U postgres --clean --if-exists shop </dev/null > out/pg-snapshot.sql
  echo "===== pg: 녹화와 세 수준의 변환 ====="
  log_on
  docker compose exec -T pg16 pgbench -U postgres -n -c 8 -j 4 -T 30 -R 200 -M prepared shop </dev/null > out/pg-record.txt 2>&1
  $P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
  sleep 1
  docker compose exec -T pg16 cat $CSV </dev/null > out/pg-prod.csv
  for level in $ALL_LEVELS; do
    $RUN /app/bin/dbreplay convert --from postgres-csv --in /exp/out/pg-prod.csv --out "/exp/out/pg-prod-$level.trace.jsonl" --mask "$level" </dev/null | tee "out/pg-convert-$level.txt"
  done
  rm -f out/pg-prod.csv
  for level in $ALL_LEVELS; do
    echo "[$level]"; grep -m1 '"sql":"UPDATE pgbench_accounts' "out/pg-prod-$level.trace.jsonl" | cut -c1-220
    grep -m1 '"sql":"INSERT INTO pgbench_history' "out/pg-prod-$level.trace.jsonl" | cut -c1-260
  done > out/pg-samples.txt
  measure pg "$URL" postgres pg-snapshot.sql "$ALL_LEVELS"
fi
echo "끝"
