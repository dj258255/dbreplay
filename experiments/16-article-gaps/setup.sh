#!/bin/sh
# 두 기종의 주종을 세우고 데이터를 넣는다. 확인 스크립트들(check-*.sh)이 이 구성을 함께 쓴다.
# 실험 11의 구성과 같다. MySQL은 GTID 복제, PostgreSQL은 스트리밍 복제다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d >/dev/null 2>&1

until mp -e "select 1" >/dev/null && mr -e "select 1" >/dev/null; do sleep 2; done
mp -e "RESET BINARY LOGS AND GTIDS"
mr -e "RESET BINARY LOGS AND GTIDS"
mp -e "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%'"
mr -e "CHANGE REPLICATION SOURCE TO SOURCE_HOST='mysql-primary', SOURCE_USER='repl', SOURCE_PASSWORD='repl', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA"
mp -e "CREATE USER 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'"
sleep 3
for host in mysql-primary mysql-replica; do
  docker compose exec -T $host mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
done
sb mysql-primary oltp_read_write --threads=4 prepare >/dev/null
mr -e "SET GLOBAL super_read_only = ON"
mr -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running:|Replica_SQL_Running:"

until pp -c "select 1" >/dev/null 2>&1 && pr -c "select 1" >/dev/null 2>&1; do sleep 2; done
docker compose exec -T pg-primary pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
pp -c "SELECT application_name, state FROM pg_stat_replication"
echo "준비됨"
