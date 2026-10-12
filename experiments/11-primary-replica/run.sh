#!/bin/sh
# 두 기종의 주종 구성에서
#   1) 주 DB와 종 DB의 로그를 따로 녹화해 따로 변환한다
#   2) 1배속으로 세 가지를 3번씩 튼다: 주만(P), 종만(R), 주와 종을 함께(B)
#   3) 함께 틀며 탐색(3번)하고, 주만 틀며 탐색(3번)한다. 알람은 실행 중인 연결 32개 이상 또는 복제 지연 1초 이상
# 인자로 mysql이나 postgres를 주면 그 기종만 돈다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
ONLY=${1:-both}
NET=dbreplay-exp11_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
ALARMS="active_sessions>=32,replication_lag_seconds>=1"
RUNS="1 2 3"

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
# 복제를 처음부터 다시 건다. 남아 있는 데이터가 있으면 GTID가 어긋난다
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d >/dev/null 2>&1

# $1 꼬리표 접두사, $2 주 URL, $3 종 URL, $4 계정, $5 덤프, 나머지는 되돌리기에 더 줄 옵션
# 주만(P), 종만(R), 함께(B)를 번갈아 3번씩 튼다
replays() {
  tag=$1; p=$2; r=$3; user=$4; snap=$5
  for i in $RUNS; do
    for mode in p r b; do
      $RUN /app/bin/dbreplay reset --url "$p" --replica-url "$r" --user "$user" --snapshot "/exp/out/$snap" </dev/null > "out/$tag-reset.txt"
      case $mode in
        p) $RUN /app/bin/dbreplay replay --trace "/exp/out/$tag-primary.trace.jsonl" --url "$p" --replica-url "$r" --user "$user" --metrics on </dev/null > "out/$tag-p-$i.txt" ;;
        r) $RUN /app/bin/dbreplay replay --trace "/exp/out/$tag-replica.trace.jsonl" --url "$r" --user "$user" --unit statement --metrics on </dev/null > "out/$tag-r-$i.txt" ;;
        b) $RUN /app/bin/dbreplay replay --trace "/exp/out/$tag-primary.trace.jsonl" --url "$p" --replica-url "$r" --user "$user" \
             --replica-trace "/exp/out/$tag-replica.trace.jsonl" --metrics on </dev/null > "out/$tag-b-$i.txt" ;;
      esac
      echo "--- $tag $mode $i: $(grep -E '^replication_lag_seconds' "out/$tag-$mode-$i.txt" || echo '복제 지연 없음')"
    done
  done
  tail -2 "out/$tag-reset.txt"
}
# 같은 인자. 함께 틀며 탐색하고 주만 틀며 탐색한다
explores() {
  tag=$1; p=$2; r=$3; user=$4; snap=$5
  for i in $RUNS; do
    $RUN /app/bin/dbreplay explore --trace "/exp/out/$tag-primary.trace.jsonl" --url "$p" --replica-url "$r" --user "$user" \
      --replica-trace "/exp/out/$tag-replica.trace.jsonl" --snapshot "/exp/out/$snap" --alarm-metric "$ALARMS" --max-speed 32 </dev/null > "out/$tag-explore-both-$i.txt"
    echo "--- $tag 함께 $i: $(grep -E '^상한' "out/$tag-explore-both-$i.txt")"
    $RUN /app/bin/dbreplay explore --trace "/exp/out/$tag-primary.trace.jsonl" --url "$p" --replica-url "$r" --user "$user" \
      --snapshot "/exp/out/$snap" --alarm-metric "$ALARMS" --max-speed 32 </dev/null > "out/$tag-explore-primary-$i.txt"
    echo "--- $tag 주만 $i: $(grep -E '^상한' "out/$tag-explore-primary-$i.txt")"
  done
}

if [ "$ONLY" != postgres ]; then
  echo "===== MySQL 8.4: 주종 세우기 ====="
  mp() { docker compose exec -T mysql-primary mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
  mr() { docker compose exec -T mysql-replica mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
  sb() { host=$1; script=$2; shift 2
    docker run --rm --network $NET dbreplay-exp01-sysbench sysbench "$script" --mysql-host="$host" --mysql-user=sb --mysql-password=sb \
      --mysql-db=sbtest --tables=4 --table-size=100000 "$@" </dev/null; }
  until mp -e "select 1" >/dev/null && mr -e "select 1" >/dev/null; do sleep 2; done
  # 이미지의 초기화가 만든 계정과 데이터베이스는 두 서버에 이미 있다. 그 기록을 지우고 여기서부터 복제한다
  mp -e "RESET BINARY LOGS AND GTIDS"
  mr -e "RESET BINARY LOGS AND GTIDS"
  mp -e "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%'"
  mr -e "CHANGE REPLICATION SOURCE TO SOURCE_HOST='mysql-primary', SOURCE_USER='repl', SOURCE_PASSWORD='repl', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA"
  mp -e "CREATE USER 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'"
  sleep 3
  mr -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running:|Replica_SQL_Running:|Last_Error:" | tee out/mysql-replica-status.txt
  for host in mysql-primary mysql-replica; do
    docker compose exec -T $host mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
  done
  sb mysql-primary oltp_read_write --threads=4 prepare >/dev/null
  mr -e "SET GLOBAL super_read_only = ON"
  # GTID를 켠 서버의 덤프에는 GTID_PURGED를 고치는 문장이 들어간다. 되부을 때 걸리므로 뺀다
  docker compose exec -T mysql-primary sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction --set-gtid-purged=OFF sbtest 2>/dev/null' </dev/null > out/mysql-snapshot.sql
  MP="jdbc:mysql://mysql-primary:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
  MR="jdbc:mysql://mysql-replica:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
  $RUN /app/bin/dbreplay reset --url "$MP" --replica-url "$MR" --user root --snapshot /exp/out/mysql-snapshot.sql </dev/null

  echo "===== MySQL: 녹화 (주에는 읽기와 쓰기, 종에는 읽기) ====="
  for f in mp mr; do $f -e "SET GLOBAL slow_query_log=OFF"; done
  for host in mysql-primary mysql-replica; do docker compose exec -T $host rm -f /var/lib/mysql/slow.log </dev/null; done
  for f in mp mr; do $f -e "SET GLOBAL slow_query_log=ON"; done
  sb mysql-replica oltp_read_only --threads=8 --time=30 --rate=200 --report-interval=0 run > out/mysql-record-replica.txt &
  sb mysql-primary oltp_read_write --threads=8 --time=30 --rate=200 --report-interval=0 run > out/mysql-record-primary.txt
  wait
  for f in mp mr; do $f -e "SET GLOBAL slow_query_log=OFF"; done
  for role in primary replica; do
    docker compose exec -T mysql-$role cat /var/lib/mysql/slow.log </dev/null > "out/mysql-$role-slow.log"
    $RUN /app/bin/dbreplay convert --in "/exp/out/mysql-$role-slow.log" --out "/exp/out/mysql-$role.trace.jsonl" </dev/null | tee "out/mysql-$role-convert.txt"
    test "$(wc -l < "out/mysql-$role.trace.jsonl")" -ge 60000
    rm -f "out/mysql-$role-slow.log"
  done

  echo "===== MySQL: 1배속 (주만 p, 종만 r, 함께 b) ====="
  replays mysql "$MP" "$MR" root mysql-snapshot.sql
  echo "===== MySQL: 탐색 ====="
  explores mysql "$MP" "$MR" root mysql-snapshot.sql
fi

if [ "$ONLY" != mysql ]; then
  echo "===== PostgreSQL 16: 주종 확인 ====="
  pp() { docker compose exec -T pg-primary psql -U postgres -d shop -q -t -A "$@" </dev/null; }
  pr() { docker compose exec -T pg-replica psql -U postgres -d shop -q -t -A "$@" </dev/null; }
  CSV=/var/lib/postgresql/data/log/pg.csv
  until pp -c "select 1" >/dev/null 2>&1 && pr -c "select 1" >/dev/null 2>&1; do sleep 2; done
  pp -c "SELECT application_name, state, sync_state FROM pg_stat_replication" | tee out/pg-replica-status.txt
  test -s out/pg-replica-status.txt
  docker compose exec -T pg-primary pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
  docker compose exec -T pg-primary pg_dump -U postgres --clean --if-exists shop </dev/null > out/pg-snapshot.sql
  PP="jdbc:postgresql://pg-primary:5432/shop?preferQueryMode=simple"
  PR="jdbc:postgresql://pg-replica:5432/shop?preferQueryMode=simple"
  $RUN /app/bin/dbreplay reset --url "$PP" --replica-url "$PR" --user postgres --snapshot /exp/out/pg-snapshot.sql </dev/null

  echo "===== PostgreSQL: 녹화 (주에는 pgbench의 기본 트랜잭션, 종에는 조회만) ====="
  # ALTER SYSTEM은 그 서버의 파일만 고친다. 복제되지 않으므로 두 서버에 따로 건다
  for role in primary replica; do
    docker compose exec -T -u postgres pg-$role sh -c ": > $CSV" </dev/null
    docker compose exec -T pg-$role psql -U postgres -d shop -q -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
  done
  sleep 1
  docker compose exec -T pg-replica pgbench -U postgres -n -S -c 8 -j 4 -T 30 -R 1500 -M prepared shop </dev/null > out/pg-record-replica.txt 2>&1 &
  docker compose exec -T pg-primary pgbench -U postgres -n -c 8 -j 4 -T 30 -R 200 -M prepared shop </dev/null > out/pg-record-primary.txt 2>&1
  wait
  for role in primary replica; do
    docker compose exec -T pg-$role psql -U postgres -d shop -q -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
  done
  sleep 1
  for role in primary replica; do
    docker compose exec -T pg-$role cat $CSV </dev/null > "out/pg-$role.csv"
    $RUN /app/bin/dbreplay convert --from postgres-csv --in "/exp/out/pg-$role.csv" --out "/exp/out/pg-$role.trace.jsonl" </dev/null | tee "out/pg-$role-convert.txt"
    test "$(wc -l < "out/pg-$role.trace.jsonl")" -ge 30000
    rm -f "out/pg-$role.csv"
  done

  echo "===== PostgreSQL: 1배속 (주만 p, 종만 r, 함께 b) ====="
  replays pg "$PP" "$PR" postgres pg-snapshot.sql
  echo "===== PostgreSQL: 탐색 ====="
  explores pg "$PP" "$PR" postgres pg-snapshot.sql
fi
echo "끝"
