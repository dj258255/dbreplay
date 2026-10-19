#!/bin/sh
# DB 없이 재생기만 돌려 재생기 혼자서도 15~20배속에서 막히는지 잰다(#117). 실험 22의 트래픽 파일을 아무것도 하지 않는
# 대상에 --only reads와 같은 옵션으로 튼다. 배속 8~64를 한 바퀴씩 REPEAT번(기본 3) 돈다. 재생기는 실험 22와 같은
# 도커 안에서 돈다. 약 2분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
TRACE=${TRACE:-../22-workers/out/prod.trace.jsonl}   # 실험 22의 run.sh가 녹화한 것
REPEAT=${REPEAT:-3}; O=${OUT:-out}
JAVAC=${JAVAC:-$(/usr/libexec/java_home -v 25)/bin/javac}

test -s "$TRACE"
mkdir -p $O/classes
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
"$JAVAC" -d $O/classes -cp "$ROOT/build/install/dbreplay/lib/*" Bench.java
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
cp "$TRACE" $O/trace.jsonl
t0=$(date +%s)
docker run --rm -v "$ROOT/build/install/dbreplay:/app:ro" -v "$HERE/$O:/out" dbreplay-exp-runner \
  java -cp '/app/lib/*:/out/classes' Bench /out/trace.jsonl "$REPEAT" 8 16 24 32 48 64 </dev/null | tee $O/bench.txt
echo "$t0 $(date +%s)" > $O/times.txt
rm -f $O/trace.jsonl
python3 summarize.py | tee $O/summary.md
