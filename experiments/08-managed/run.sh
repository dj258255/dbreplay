#!/bin/sh
# MySQL과 PostgreSQL 대상을 하나씩 등록해 두고 manage를 세 번 돌린다.
# 1) 처음: 둘 다 평가한다  2) 바로 다시: 둘 다 그대로 둔다  3) MySQL의 파라미터 하나를 바꾼 뒤: MySQL만 다시 평가한다
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp08_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
CSV=/var/lib/postgresql/data/log/pg.csv

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
rm -f out/eval-*.json

# MySQL 녹화
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T mysql84 sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > out/mysql-snapshot.sql
docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null >/dev/null
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/mysql-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/mysql-slow.log --out /exp/out/mysql.trace.jsonl </dev/null >/dev/null
test "$(wc -l < out/mysql.trace.jsonl)" -ge 100000

# PostgreSQL 녹화
docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
docker compose exec -T pg16 pg_dump -U postgres --clean --if-exists shop </dev/null > out/pg-snapshot.sql
docker compose exec -T -u postgres pg16 sh -c ": > $CSV" </dev/null
$P -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" </dev/null >/dev/null; sleep 1
docker compose exec -T pg16 pgbench -U postgres -n -c 8 -j 4 -T 30 -R 200 -M prepared shop </dev/null >/dev/null 2>&1
$P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null; sleep 1
docker compose exec -T pg16 cat $CSV </dev/null > out/pg.csv
$RUN /app/bin/dbreplay convert --from postgres-csv --in /exp/out/pg.csv --out /exp/out/pg.trace.jsonl </dev/null >/dev/null
test "$(wc -l < out/pg.trace.jsonl)" -ge 30000

echo "##### 1) 처음"
$RUN /app/bin/dbreplay manage --targets /exp/targets.json </dev/null | tee out/manage-1.txt
echo "##### 2) 바로 다시"
$RUN /app/bin/dbreplay manage --targets /exp/targets.json </dev/null | tee out/manage-2.txt
echo "##### 3) MySQL의 innodb_io_capacity를 바꾼 뒤"
$M -e "SET GLOBAL innodb_io_capacity = 2000" 2>/dev/null </dev/null
$RUN /app/bin/dbreplay manage --targets /exp/targets.json </dev/null | tee out/manage-3.txt
