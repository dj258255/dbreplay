#!/bin/sh
# #53: 종 DB를 함께 튼 탐색이 종 DB의 수위를 따로 내고, 평가 파일과 check, report에 남기는가. setup.sh와 record.sh를 먼저 돌린다.
# 수치가 아니라 기능을 본다. 상한의 값은 이 확인에서 쓰지 않는다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

echo "== 종 DB를 함께 튼 탐색 (종 DB의 지금 QPS를 녹화의 3배로)"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --replica-url "$MR" --replica-trace /exp/out/replica.trace.jsonl \
  --user root --snapshot /exp/out/base.sql --alarm-metric "active_sessions>=32" --max-speed 16 \
  --current-replica-qps 9000 --read-nodes 2 --max-read-nodes 6 --out /exp/out/53-evaluation.json </dev/null > out/53-explore.txt 2>&1 || true
grep -E "^상한|^녹화 구간|^수위|^종 DB|읽기 노드의 권고|^  읽기:|^  쓰기:|노드" out/53-explore.txt | cut -c1-200
python3 -c "import json; e = json.load(open('out/53-evaluation.json')); r = e.get('replica'); print('평가 파일의 replica:', None if r is None else {k: round(v) for k, v in r.items() if k != 'traffic'})"

echo "== check"
$RUN /app/bin/dbreplay check --evaluation /exp/out/53-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
  --current-replica-qps 15000 </dev/null | head -4

echo "== report"
$RUN /app/bin/dbreplay report --before /exp/out/primary.trace.jsonl --after /exp/out/primary.trace.jsonl \
  --before-evaluation /exp/out/53-evaluation.json --after-evaluation /exp/out/52-single.json --out /exp/out/53-report.md </dev/null >/dev/null 2>&1 \
  || $RUN /app/bin/dbreplay report --before /exp/out/primary.trace.jsonl --after /exp/out/primary.trace.jsonl \
       --before-evaluation /exp/out/53-evaluation.json --after-evaluation /exp/out/53-evaluation.json --out /exp/out/53-report.md </dev/null >/dev/null
grep -E "^\| (수위|종 DB의 수위) " out/53-report.md

echo "== 종 DB 없이 탐색한 평가는 그대로인가"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --alarm-metric "active_sessions>=32" --max-speed 2 --out /exp/out/53-primary-only.json </dev/null | grep -cE "^종 DB" || true
grep -c '"replica"' out/53-primary-only.json || true

echo "== 종 DB의 수위로 낸 권고는 확장 명령을 실행하지 않는가 (--apply-scale yes를 줘도)"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --replica-url "$MR" --replica-trace /exp/out/replica.trace.jsonl \
  --user root --snapshot /exp/out/base.sql --alarm-metric "active_sessions>=32" --max-speed 16 \
  --current-replica-qps 9000 --read-nodes 2 --max-read-nodes 6 --scale-command 'echo "확장 명령이 실행됐다: $DBREPLAY_READ_NODES"' --apply-scale yes \
  </dev/null > out/53-scale.txt 2>&1 || true
grep -E "읽기 노드의 권고|노드 [0-9]|확장|실행" out/53-scale.txt | cut -c1-200

echo "== check에 잘못된 수"
$RUN /app/bin/dbreplay check --evaluation /exp/out/53-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
  --current-replica-qps NaN </dev/null 2>&1 | grep -m1 -E "IllegalArgument|수를 준다" | cut -c1-160
