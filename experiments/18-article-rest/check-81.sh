#!/bin/sh
# #81 쓰기만의 탐색, 나눠 트는 재생. (1) explore --only writes가 도는가 (2) explore --only reads --workers 2가 도는가
# (3) replay --shard 1/2와 2/2를 같은 --start-at으로 함께 틀면 문장 수의 합이 전체와 같은가. record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
echo "== --only writes"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --only writes --alarm-metric "active_sessions>=400" --start-speed 1 --max-speed 2 </dev/null > out/81-writes.txt 2>&1 || true
grep -E "^1 |^2 |^상한|쓰기만|수위" out/81-writes.txt | head
echo "== --only reads --workers 2"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --only reads --workers 2 --alarm-metric "active_sessions>=400" --start-speed 1 --max-speed 2 </dev/null > out/81-workers.txt 2>&1 || true
grep -E "^1 |^2 |^상한|읽기만" out/81-workers.txt | head
echo "== replay --shard 1/2, 2/2 (같은 시각에 시작)"
$RUN /app/bin/dbreplay reset --url "$MP" --user root --snapshot /exp/out/base.sql </dev/null >/dev/null
at=$(( $(date +%s) * 1000 + 8000 ))
$RUN /app/bin/dbreplay replay --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --shard 1/2 --start-at $at </dev/null > out/81-shard1.txt 2>&1 &
$RUN /app/bin/dbreplay replay --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --shard 2/2 --start-at $at </dev/null > out/81-shard2.txt 2>&1
wait
grep -hE "^문장|실행한 문장|문장 [0-9]" out/81-shard1.txt out/81-shard2.txt | head -4
python3 -c "
import json; l=open('out/primary.trace.jsonl').read().splitlines()[1:]; print('트래픽 파일의 문장 수:', sum(1 for x in l if '\"sql\"' in x))"
