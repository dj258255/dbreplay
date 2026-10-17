#!/bin/sh
# #87 등록 규칙과 상주 평가. 대상 둘을 등록한다: 모양이 같은 트래픽 둘을 가진 것(든다)과 주 DB와 종 DB의 트래픽처럼 모양이 다른 둘을
# 가진 것(들지 않는다). manage --every 1로 띄워 두 번 훑는 것을 보고, 멈추라는 신호에 멈추는지 본다. record.sh를 먼저 돌린다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
rm -f out/87-*.json
cat > out/87-targets.json <<JSON
{"targets": [
  {"name": "stable", "evaluation": "/exp/out/87-stable.json",
   "traces": ["/exp/out/primary.trace.jsonl", "/exp/out/primary.trace.jsonl"],
   "options": {"--trace": "/exp/out/primary.trace.jsonl", "--url": "$MP", "--user": "root", "--snapshot": "/exp/out/base.sql",
               "--alarm-metric": "active_sessions>=400", "--start-speed": "1", "--max-speed": "2"}},
  {"name": "unstable", "evaluation": "/exp/out/87-unstable.json",
   "traces": ["/exp/out/primary.trace.jsonl", "/exp/out/replica.trace.jsonl"],
   "options": {"--trace": "/exp/out/primary.trace.jsonl", "--url": "$MP", "--user": "root", "--snapshot": "/exp/out/base.sql",
               "--alarm-metric": "active_sessions>=400", "--start-speed": "1", "--max-speed": "2"}}
]}
JSON
docker run -d --name dbreplay-exp18-manage --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay \
  dbreplay-exp-runner /app/bin/dbreplay manage --targets /exp/out/87-targets.json --every 1 --register-max-distance ${DIST:-0.2} --register-min-qps 100 >/dev/null
sleep 150
docker stop -t 120 dbreplay-exp18-manage >/dev/null
docker logs dbreplay-exp18-manage 2>&1 | grep -E "#####|^stable|^unstable|멈췄다|===" > out/87-manage.txt
docker rm dbreplay-exp18-manage >/dev/null
cat out/87-manage.txt
