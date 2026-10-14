#!/bin/sh
# #68: 라운드 도중에 표본기의 연결이 끊기면 explore가 멈추는가. setup.sh와 record.sh를 먼저 돌린다.
# 탐색을 띄워 두고, 표본기의 연결(마지막 문장이 /* dbreplay:sampler */로 시작하는 연결)을 서버에서 끊는다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --alarm-metric "active_sessions>=32" --max-speed 2 </dev/null > out/68-explore.txt 2>&1 &
PID=$!
FIND="SELECT t.PROCESSLIST_ID FROM performance_schema.events_statements_current s JOIN performance_schema.threads t USING (THREAD_ID)
      WHERE s.SQL_TEXT LIKE '/* dbreplay:sampler */%' AND t.PROCESSLIST_ID <> CONNECTION_ID()"
# 첫 라운드의 재생이 시작돼 표본기가 붙을 때까지 1초마다 찾는다(되돌리기에 5초쯤 걸린다)
ids=""; n=0
while [ -z "$ids" ] && [ $n -lt 40 ]; do sleep 1; n=$((n+1)); ids=$(mp -N -e "$FIND" | sort -u | tr '\n' ' '); done
sleep 2
echo "끊을 표본기 연결 (${n}초 뒤): ${ids:-없음}"
for id in $ids; do mp -e "KILL $id" || true; done
if wait $PID; then echo "탐색이 끝까지 갔다 (멈추지 않았다)"; else echo "탐색이 멈췄다 (종료 코드 $?)"; fi
grep -E "^[0-9]+ |지표를 읽다|^상한" out/68-explore.txt | cut -c1-260

echo "== replay는 끝까지 가고 알린다"
$RUN /app/bin/dbreplay replay --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --speed 1 --metrics on </dev/null > out/68-replay.txt 2>&1 &
PID=$!
sleep 4
ids=$(mp -N -e "$FIND" | sort -u | tr '\n' ' ')
for id in $ids; do mp -e "KILL $id" || true; done
wait $PID && echo "replay 종료 코드 0" || echo "replay 종료 코드 $?"
grep -E "^문장|지표를 읽다" out/68-replay.txt | cut -c1-200
