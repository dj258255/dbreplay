#!/bin/sh
# 확인에 쓸 짧은 트래픽을 뜬다. 주 DB에 읽기와 쓰기, 종 DB에 읽기를 10초 동안 함께 건다. 스냅샷도 뜬다.
# out/primary.trace.jsonl, out/replica.trace.jsonl, out/base.sql이 생긴다. setup.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

$RUN /app/bin/dbreplay snapshot --url "$MP" --user root --out /exp/out/base.sql </dev/null
for f in mp mr; do $f -e "SET GLOBAL slow_query_log=OFF"; done
for host in mysql-primary mysql-replica; do docker compose exec -T $host rm -f /var/lib/mysql/slow.log </dev/null; done
for f in mp mr; do $f -e "SET GLOBAL slow_query_log=ON"; done
sb mysql-replica oltp_read_only --threads=8 --time=10 --rate=200 --report-interval=0 run >/dev/null &
sb mysql-primary oltp_read_write --threads=8 --time=10 --rate=200 --report-interval=0 run >/dev/null
wait
for f in mp mr; do $f -e "SET GLOBAL slow_query_log=OFF"; done
for role in primary replica; do
  docker compose exec -T mysql-$role cat /var/lib/mysql/slow.log </dev/null > "out/$role-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$role-slow.log" --out "/exp/out/$role.trace.jsonl" </dev/null
  rm -f "out/$role-slow.log"
done
