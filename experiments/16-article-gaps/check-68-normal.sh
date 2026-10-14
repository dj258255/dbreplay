#!/bin/sh
# #68: 아무것도 끊지 않은 탐색이 실패를 잘못 적어 멈추지 않는가. 주종 함께, 지표 셋(DB, 복제 지연, 명령)으로. setup.sh와 record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --replica-url "$MR" --replica-trace /exp/out/replica.trace.jsonl \
  --user root --snapshot /exp/out/base.sql --alarm-metric "active_sessions>=32" --metric-command "sh /exp/host.sh" --repeat 2 --max-speed 16 \
  </dev/null > out/68-normal.txt 2>&1 && echo "탐색이 끝까지 갔다" || echo "탐색이 멈췄다 (종료 코드 $?)"
grep -E "^상한|지표를 읽다|Exception" out/68-normal.txt | cut -c1-200
