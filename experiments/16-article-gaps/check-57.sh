#!/bin/sh
# #57: --profile-command가 낸 값이 평가 파일에 남고, 그 값이 바뀌면 check가 무효로 답하는가. setup.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

# 평가 파일을 빨리 얻으려고 짧은 트래픽을 만든다. 상한의 값은 이 확인에서 보지 않는다
$RUN /app/bin/dbreplay snapshot --url "$MP" --user root --out /exp/out/57.sql </dev/null >/dev/null
mp -e "SET GLOBAL slow_query_log=OFF"; docker compose exec -T mysql-primary rm -f /var/lib/mysql/slow.log </dev/null; mp -e "SET GLOBAL slow_query_log=ON"
sb mysql-primary oltp_read_write --threads=4 --time=5 --rate=50 --report-interval=0 run >/dev/null
mp -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql-primary cat /var/lib/mysql/slow.log </dev/null > out/57-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/57-slow.log --out /exp/out/57.trace.jsonl </dev/null >/dev/null

echo "== 하드웨어를 남기며 평가"
$RUN /app/bin/dbreplay explore --trace /exp/out/57.trace.jsonl --url "$MP" --user root --snapshot /exp/out/57.sql \
  --alarm-metric "active_sessions>=32" --max-speed 2 --profile-command "sh /exp/host.sh" --out /exp/out/57-evaluation.json </dev/null | tail -2
grep '"host\.' out/57-evaluation.json
grep -c "host.sh" out/57-evaluation.json || true

echo "== 같은 장비에서 check"
$RUN /app/bin/dbreplay check --evaluation /exp/out/57-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
  --profile-command "sh /exp/host.sh" </dev/null | tee out/57-check-same.txt

echo "== CPU 수가 달라진 장비에서 check"
docker run --rm --network $NET -v "$ROOT/build/install/dbreplay:/app:ro" -v "$HERE:/exp" -e DBREPLAY_PASSWORD=replay -e FAKE_CPUS=2 dbreplay-exp-runner \
  /app/bin/dbreplay check --evaluation /exp/out/57-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
  --profile-command "sh /exp/host.sh" </dev/null | tee out/57-check-changed.txt || true

echo "== 명령을 주지 않고 check"
$RUN /app/bin/dbreplay check --evaluation /exp/out/57-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" </dev/null | tee out/57-check-nocommand.txt

echo "== 아무것도 내지 않는 명령으로 check"
$RUN /app/bin/dbreplay check --evaluation /exp/out/57-evaluation.json --url "$MP" --user root --alarm-metric "active_sessions>=32" \
  --profile-command "true" </dev/null | tee out/57-check-empty.txt
rm -f out/57.sql out/57-slow.log
