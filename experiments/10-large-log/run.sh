#!/bin/sh
# 힙을 1GB로 묶고 12GB짜리 슬로우 로그를 변환해 본다.
#   1) 300MB 로그: 메모리에서 정렬한 출력과 임시 파일로 나눠 정렬한 출력이 같은가
#   2) 12GB 로그: 바꾸기 전의 변환기는 어떻게 되는가 (OLD에 예전 빌드의 경로를 주면 돌린다)
#   3) 12GB 로그: 바꾼 변환기가 끝까지 가는가, 출력의 순서가 맞는가
# JDK 25가 있어야 한다. JAVA_HOME으로 가리킨다. 디스크는 40GB쯤 쓴다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
BIG=${BIG:-12000000000}
JAVA="$JAVA_HOME/bin/java"
NEW="$ROOT/build/install/dbreplay/bin/dbreplay"
(cd "$ROOT" && ./gradlew -q installDist)

echo "===== 1) 300MB: 나눠 정렬한 출력이 메모리에서 정렬한 출력과 같은가 ====="
"$JAVA" GenerateSlowLog.java out/small.log 300000000 out/small-expected.txt
JAVA_OPTS=-Xmx4g "$NEW" convert --in out/small.log --out out/small-memory.jsonl
JAVA_OPTS=-Xmx4g "$NEW" convert --in out/small.log --out out/small-spilled.jsonl --sort-memory-mb 8
cmp out/small-memory.jsonl out/small-spilled.jsonl && echo "두 출력이 같다"
rm -f out/small.log out/small-memory.jsonl out/small-spilled.jsonl

echo "===== 큰 로그 만들기 ====="
"$JAVA" GenerateSlowLog.java out/big.log "$BIG" out/expected.txt | tee out/generate.txt
ls -l out/big.log | awk '{printf "로그 파일 %.1fGB\n", $5 / 1e9}'

if [ -n "${OLD:-}" ]; then
  echo "===== 2) 바꾸기 전의 변환기, 힙 1GB ====="
  start=$(date +%s)
  JAVA_OPTS=-Xmx1g "$OLD" convert --in out/big.log --out out/big-old.jsonl > out/convert-old.txt 2>&1 && echo "끝까지 갔다" || echo "실패했다"
  echo "걸린 시간 $(( $(date +%s) - start ))초"
  grep -m1 -E "OutOfMemoryError|로그 항목" out/convert-old.txt || true
  rm -f out/big-old.jsonl
fi

echo "===== 3) 바꾼 변환기, 힙 1GB ====="
JAVA_OPTS=-Xmx1g /usr/bin/time -l "$NEW" convert --in out/big.log --out out/big.jsonl > out/convert-new.txt 2> out/convert-new-time.txt
cat out/convert-new.txt
awk '/real/ {printf "걸린 시간 %.0f초\n", $1} /maximum resident set size/ {printf "가장 컸던 메모리(RSS) %.2fGB\n", $1 / 1e9}' out/convert-new-time.txt
ls -l out/big.jsonl | awk '{printf "트래픽 파일 %.1fGB\n", $5 / 1e9}'
ls out | grep -c "dbreplay-convert" | awk '{print "남은 임시 폴더 " $1 "개"}' || true

echo "===== 검사 ====="
FAILED=0
if "$JAVA" -Xmx1g CheckTrace.java out/big.jsonl > out/actual.txt 2> out/check.txt; then echo "순서가 맞다"; else echo "순서가 틀렸다"; FAILED=1; fi
tail -3 out/check.txt
if diff out/expected.txt out/actual.txt; then echo "문장 수, 연결 종료 수, 연결 수가 만든 것과 같다"; else FAILED=1; fi
rm -f out/big.log out/big.jsonl
echo "끝"
exit $FAILED
