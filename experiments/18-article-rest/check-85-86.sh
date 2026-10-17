#!/bin/sh
# #85 존별 권고와 분할 권고, #86 확장 뒤 확인과 되돌리기와 기록. 알람(qps)을 1배속과 2배속 사이에 걸어 상한이 알람으로 확인되게 한다.
# 확인 명령은 일부러 실패시킨다. record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
rm -f out/85-scale.log out/85-scale-calls.txt
set -- $(python3 -c "
import json; l=open('out/primary.trace.jsonl').read().splitlines()[1:]; r=[json.loads(x) for x in l if '\"sql\"' in x]
span=(r[-1]['startMicros']-r[0]['startMicros'])/1e6
reads=sum(1 for x in r if x['sql'].lstrip().upper().startswith('SELECT'))/span
writes=sum(1 for x in r if x['sql'].lstrip().upper().split(' ')[0] in ('INSERT','UPDATE','DELETE'))/span
print(int(len(r)/span*1.5), int(reads*1.2), int(writes*4))")
qps=$1; read=$2; write=$3
echo "녹화의 초당 문장 수의 1.5배에 알람: qps>=$qps. 지금의 읽기는 녹화의 1.2배($read), 쓰기는 4배($write)로 준다"
$RUN /app/bin/dbreplay explore --trace /exp/out/primary.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
  --alarm-metric "qps>=$qps" --alarm-sustain 2 --start-speed 1 --max-speed 8 --current-read-qps $read --current-write-qps $write \
  --zones "a=2,b=1,c=1" --min-per-zone 1 --max-read-nodes 20 --max-write-scale 2 \
  --scale-command 'echo "scale $DBREPLAY_READ_NODES $DBREPLAY_READ_NODES_BY_ZONE" >> /exp/out/85-scale-calls.txt' \
  --scale-verify-command 'echo "verify $DBREPLAY_READ_NODES" >> /exp/out/85-scale-calls.txt; exit 3' \
  --scale-rollback-command 'echo "rollback $DBREPLAY_READ_NODES $DBREPLAY_READ_NODES_BY_ZONE" >> /exp/out/85-scale-calls.txt' \
  --scale-log /exp/out/85-scale.log --apply-scale yes </dev/null > out/85-explore.txt 2>&1 || echo "explore 종료 코드 $?"
grep -E "^상한|수위|존별|빠지면|분할|읽기:|쓰기:|확장|확인 명령|되돌리기" out/85-explore.txt | head -20
echo "== 불린 명령"; cat out/85-scale-calls.txt 2>/dev/null || echo "(없음)"
echo "== 실행 기록"; cat out/85-scale.log 2>/dev/null || echo "(없음)"
