#!/bin/sh
# 기본 데이터베이스가 서로 다른 세 연결을 섞어 돌려 `use db;` 줄이 언제 찍히는지 본다.
set -eu
cd "$(dirname "$0")/.."
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay --comments"
$M -e "CREATE DATABASE IF NOT EXISTS other" 2>/dev/null </dev/null
docker compose exec -T mysql84 sh -c ': > /var/lib/mysql/slow.log' </dev/null
$M -e "FLUSH SLOW LOGS" 2>/dev/null </dev/null
printf 'SELECT /* a1 shop */ 1;\nDO /* a2 shop */ SLEEP(0.3);\nSELECT /* a3 shop */ 2;\n' | $M shop >/dev/null 2>&1 &
printf 'DO /* b1 other */ SLEEP(0.1);\nSELECT /* b2 other */ 3;\nDO /* b3 other */ SLEEP(0.4);\nSELECT /* b4 other */ 4;\n' | $M other >/dev/null 2>&1 &
printf 'DO /* c1 none */ SLEEP(0.2);\nSELECT /* c2 none */ 5;\nDO /* c3 none */ SLEEP(0.45);\nSELECT /* c4 none */ 6;\n' | $M >/dev/null 2>&1 &
wait
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/mysql-use-db.log
grep -vE '^# (Time|Query_time)|^SET timestamp|version_comment|mysqld|Tcp port|^Time |administrator|FLUSH' out/mysql-use-db.log \
  | sed -E 's/^# User@Host:.*Id: +/  conn /'
