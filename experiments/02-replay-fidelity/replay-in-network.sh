#!/bin/sh
# 재생기를 DB와 같은 도커 네트워크 안에서 돌린다. 호스트에서 돌리면 포트 포워딩 왕복 시간이 문장마다 붙는다.
# 사용: ./replay-in-network.sh <배속> [결과 꼬리표]
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
speed=$1
label=${2:-net}
cd "$HERE"
./reset.sh
docker run --rm --network dbreplay-exp02_default \
  -v "$ROOT/build/install/dbreplay:/app:ro" -v "$HERE/out:/data" -e DBREPLAY_PASSWORD=replay \
  eclipse-temurin:25-jre /app/bin/dbreplay replay --trace /data/trace.jsonl \
  --url "jdbc:mysql://mysql84:3306/?sslMode=DISABLED&allowPublicKeyRetrieval=true" \
  --user root --unit session --speed "$speed" --out "/data/results-${label}-${speed}x.jsonl" </dev/null | tee "out/replay-${label}-${speed}x.txt"
