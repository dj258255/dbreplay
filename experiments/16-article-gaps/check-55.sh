#!/bin/sh
# #55, #66: 느리게 끝난 문장의 수가 지표로 나오고 알람으로 걸리는가. 없는 지표에 건 알람은 멈추는가. setup.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

$RUN /app/bin/dbreplay snapshot --url "$MP" --user root --out /exp/out/55.sql </dev/null >/dev/null
mp -e "SET GLOBAL slow_query_log=OFF"; docker compose exec -T mysql-primary rm -f /var/lib/mysql/slow.log </dev/null; mp -e "SET GLOBAL slow_query_log=ON"
sb mysql-primary oltp_read_write --threads=8 --time=20 --rate=200 --report-interval=0 run >/dev/null
mp -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql-primary cat /var/lib/mysql/slow.log </dev/null > out/55-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/55-slow.log --out /exp/out/55.trace.jsonl </dev/null >/dev/null
rm -f out/55-slow.log

echo "== 서버가 센 값과 견준다 (16배속 재생 한 번)"
$RUN /app/bin/dbreplay reset --url "$MP" --replica-url "$MR" --user root --snapshot /exp/out/55.sql </dev/null >/dev/null
SLOW="SELECT COALESCE(SUM(COUNT_BUCKET), 0) FROM performance_schema.events_statements_histogram_global WHERE BUCKET_TIMER_LOW >= 100000000000"
before=$(mp -N -e "$SLOW")
$RUN /app/bin/dbreplay replay --trace /exp/out/55.trace.jsonl --url "$MP" --user root --speed 16 --metrics on </dev/null > out/55-replay.txt
after=$(mp -N -e "$SLOW")
echo "서버의 분포로 센 100ms 넘는 문장: $((after - before))개"
grep -E "^지표|^slow_finished_total|^qps" out/55-replay.txt

echo "== 누적 지표에 알람을 걸어 탐색"
$RUN /app/bin/dbreplay explore --trace /exp/out/55.trace.jsonl --url "$MP" --replica-url "$MR" --user root --snapshot /exp/out/55.sql \
  --alarm-total "slow_finished_total>=50" --max-speed 32 </dev/null > out/55-explore.txt
grep -E "^[0-9]+ |^상한" out/55-explore.txt | cut -c1-150

echo "== 이름을 잘못 적은 알람 (멈춰야 한다)"
$RUN /app/bin/dbreplay explore --trace /exp/out/55.trace.jsonl --url "$MP" --user root --snapshot /exp/out/55.sql \
  --alarm-metric "active_session>=32" --max-speed 4 </dev/null > out/55-typo.txt 2>&1 && echo "멈추지 않았다" || echo "멈췄다 (종료 코드 $?)"
grep -m1 "알람에 건 지표" out/55-typo.txt | cut -c1-240

echo "== PostgreSQL에 MySQL만 내는 지표를 건 알람 (멈춰야 한다)"
$RUN /app/bin/dbreplay snapshot --url "$PP" --user postgres --out /exp/out/55-pg.sql </dev/null >/dev/null
printf '{"format":"dbreplay-trace","version":1,"engine":"postgresql","source":"csvlog"}\n{"session":"a","startMicros":1000000,"durationMicros":100,"sql":"SELECT 1","db":"shop","error":0}\n{"session":"a","startMicros":2000000,"durationMicros":100,"sql":"SELECT 1","db":"shop","error":0}\n' > out/55-pg.trace.jsonl
$RUN /app/bin/dbreplay explore --trace /exp/out/55-pg.trace.jsonl --url "$PP" --user postgres --snapshot /exp/out/55-pg.sql \
  --alarm-total "slow_finished_total>=50" --max-speed 2 </dev/null > out/55-pg-total.txt 2>&1 && echo "멈추지 않았다" || echo "멈췄다 (종료 코드 $?)"
grep -m1 "알람에 건 지표" out/55-pg-total.txt | cut -c1-240
rm -f out/55.sql out/55-pg.sql out/55.trace.jsonl
