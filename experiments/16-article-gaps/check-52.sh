#!/bin/sh
# #52: 평가 파일에 라운드가 남고 리포트에 상승 과정이 나오는가. setup.sh와 record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh
EXPLORE="--trace /exp/out/primary.trace.jsonl --url $MP --replica-url $MR --user root --snapshot /exp/out/base.sql --alarm-metric active_sessions>=32 --max-speed 16"

echo "== 한 번 탐색한 평가와 두 번 되풀이한 평가"
$RUN /app/bin/dbreplay explore $EXPLORE --out /exp/out/52-single.json </dev/null | grep -E "^상한"
$RUN /app/bin/dbreplay explore $EXPLORE --repeat 2 --out /exp/out/52-repeat.json </dev/null | grep -E "^상한"
python3 - <<'PY'
import json
import os
for name in [n for n in ("52-single", "52-repeat") if os.path.exists(f"out/{n}.json")]:
    e = json.load(open(f"out/{name}.json"))
    print(name, "탐색", len(e["explorations"]), "개, 라운드", [len(x) for x in e["explorations"]], "개, 첫 라운드:", {k: e["explorations"][0][0][k] for k in ("speed", "verdict", "distortion")})
PY

echo "== 리포트 (변경 전은 한 번, 변경 후는 되풀이)"
$RUN /app/bin/dbreplay report --before /exp/out/primary.trace.jsonl --after /exp/out/primary.trace.jsonl \
  --before-evaluation /exp/out/52-single.json --after-evaluation /exp/out/52-repeat.json --out /exp/out/52-report.md </dev/null
sed -n '/^## 용량/,$p' out/52-report.md

echo "== 라운드를 남기지 않은 예전 평가 파일과 함께"
cp ../08-managed/out/eval-orders-mysql.json out/52-old.json
$RUN /app/bin/dbreplay report --before /exp/out/primary.trace.jsonl --after /exp/out/primary.trace.jsonl \
  --before-evaluation /exp/out/52-old.json --after-evaluation /exp/out/52-single.json --out /exp/out/52-report-old.md </dev/null
grep -A2 "상승 과정: 변경 전" out/52-report-old.md
$RUN /app/bin/dbreplay check --evaluation /exp/out/52-single.json --url "$MP" --user root --alarm-metric "active_sessions>=32" </dev/null | head -2
rm -f out/52-old.json
