#!/bin/sh
# #80 종 DB에서도 지표를 뜨고 알람을 건다. (1) 한가할 때와 주 DB에 쓰기만 걸 때 종 DB의 active_sessions가 0인가(복제 적용 스레드를 뺐는가)
# (2) 종 DB의 알람을 낮게 걸면 그 알람이 탐색을 멈추고 "종 DB의 알람으로 확인됐다"가 나오는가. record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
echo "== 한가할 때 5초"
$RUN /app/bin/dbreplay sample --url "$MP" --user root --replica-url "$MR" --seconds 5 </dev/null | grep -E "active_sessions|replication"
echo "== 주 DB에 쓰기를 거는 동안 10초 (종 DB에는 복제 적용만 있다)"
sb mysql-primary oltp_write_only --threads=8 --time=12 --rate=300 --report-interval=0 run >/dev/null &
sleep 1
$RUN /app/bin/dbreplay sample --url "$MP" --user root --replica-url "$MR" --seconds 10 </dev/null | grep -E "active_sessions|replication"
wait
echo "== 종 DB에 알람을 낮게 걸고 탐색"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --replica-url "$MR" --replica-trace /exp/out/replica.trace.jsonl --alarm-metric "replica.active_sessions>=6,active_sessions>=400" --start-speed 1 --max-speed 16 --out /exp/out/80-eval.json </dev/null > out/80-explore.txt 2>&1 || true
grep -E "과부하|^상한|종 DB" out/80-explore.txt | head -20
python3 -c "import json; print('평가 파일의 replica:', json.load(open('out/80-eval.json')).get('replica', {}).get('confirmed'))" 2>/dev/null || echo "평가 파일 없음"
