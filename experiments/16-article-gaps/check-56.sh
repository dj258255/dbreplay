#!/bin/sh
# #56: dbreplay snapshot으로 뜬 덤프를 reset으로 되부으면 바꿔 둔 행이 원래대로 돌아오는가. 두 기종. setup.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

echo "== MySQL 8.4 (GTID가 켜져 있다)"
$RUN mysqldump --version </dev/null
mp sbtest -N -e "SELECT COUNT(*), SUM(k) FROM sbtest1" | tee out/56-mysql-before.txt
$RUN /app/bin/dbreplay snapshot --url "$MP" --user root --out /exp/out/56-mysql.sql </dev/null
grep -c "GTID_PURGED" out/56-mysql.sql || true
mp sbtest -e "UPDATE sbtest1 SET k = -1 WHERE id <= 1000; DELETE FROM sbtest1 WHERE id > 99000"
echo "바꾼 뒤: $(mp sbtest -N -e "SELECT COUNT(*), SUM(k) FROM sbtest1")"
$RUN /app/bin/dbreplay reset --url "$MP" --replica-url "$MR" --user root --snapshot /exp/out/56-mysql.sql </dev/null
mp sbtest -N -e "SELECT COUNT(*), SUM(k) FROM sbtest1" | tee out/56-mysql-after.txt
echo "종 DB: $(mr sbtest -N -e "SELECT COUNT(*), SUM(k) FROM sbtest1")"
cmp out/56-mysql-before.txt out/56-mysql-after.txt && echo "MySQL: 원래대로 돌아왔다"

echo "== PostgreSQL 16"
pp -c "SELECT count(*), sum(abalance), sum(aid) FROM pgbench_accounts" | tee out/56-pg-before.txt
$RUN /app/bin/dbreplay snapshot --url "$PP" --user postgres --out /exp/out/56-pg.sql </dev/null
pp -c "UPDATE pgbench_accounts SET abalance = -7 WHERE aid <= 1000" -c "DELETE FROM pgbench_accounts WHERE aid > 499000"
echo "바꾼 뒤: $(pp -c "SELECT count(*), sum(abalance), sum(aid) FROM pgbench_accounts")"
$RUN /app/bin/dbreplay reset --url "$PP" --replica-url "$PR" --user postgres --snapshot /exp/out/56-pg.sql </dev/null
pp -c "SELECT count(*), sum(abalance), sum(aid) FROM pgbench_accounts" | tee out/56-pg-after.txt
echo "종 DB: $(pr -c "SELECT count(*), sum(abalance), sum(aid) FROM pgbench_accounts")"
cmp out/56-pg-before.txt out/56-pg-after.txt && echo "PostgreSQL: 원래대로 돌아왔다"
rm -f out/56-mysql.sql out/56-pg.sql
