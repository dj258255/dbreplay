#!/bin/sh
# #88 화면과 HTTP API. 호스트에서 serve를 띄워 화면과 API가 실험 18의 평가 파일을 보여 주는지, API로 맡긴 재생이 끝나 요약을 돌려주는지 본다.
# API는 토큰을 요구하고 재생 대상은 대상 목록의 이름으로만 받는다(보안 검토의 지적). 그 거절도 본다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
export JAVA_HOME=$(/usr/libexec/java_home -v 25) DBREPLAY_PASSWORD=replay DBREPLAY_SERVE_TOKEN=exp18-token-0123456789
cat > out/88-targets.list <<'JSON'
{"targets": [{"name": "primary", "evaluation": "80-eval.json",
  "options": {"--url": "jdbc:mysql://127.0.0.1:33061/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true", "--user": "root"}}]}
JSON
A="Authorization: Bearer $DBREPLAY_SERVE_TOKEN"
$ROOT/build/install/dbreplay/bin/dbreplay serve --dir out --targets out/88-targets.list --port 18088 > out/88-serve.txt 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 4
echo "== 첫 화면"; curl -s http://127.0.0.1:18088/ | grep -o "<h2>[^<]*</h2>\|80-eval" | sort -u
echo "== API 평가 목록"; curl -s -H "$A" http://127.0.0.1:18088/api/evaluations; echo
echo "== 토큰 없이"; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18088/api/evaluations
echo "== 다른 Host 머리"; curl -s -o /dev/null -w "%{http_code}\n" -H "$A" -H "Host: evil.example:18088" http://127.0.0.1:18088/api/evaluations
echo "== URL을 직접 주면"; curl -s -X POST -H "$A" -H 'Content-Type: application/json' http://127.0.0.1:18088/api/replays \
  -d '{"trace":"primary.trace.jsonl","url":"jdbc:mysql://evil:3306/x"}'; echo
echo "== 작업 폴더 밖"; curl -s -o /dev/null -w "%{http_code}\n" -H "$A" "http://127.0.0.1:18088/api/evaluations/..%2F..%2Fbuild"
echo "== 재생 맡기기"
r=$(curl -s -X POST -H "$A" http://127.0.0.1:18088/api/replays -H 'Content-Type: application/json' \
  -d '{"trace":"primary.trace.jsonl","target":"primary","speed":2}')
echo "$r"
id=$(echo "$r" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
curl -s -o /dev/null -w "같은 때 두 번째: %{http_code}\n" -X POST -H "$A" http://127.0.0.1:18088/api/replays -H 'Content-Type: application/json' \
  -d '{"trace":"primary.trace.jsonl","target":"primary"}'
for i in $(seq 1 30); do s=$(curl -s -H "$A" http://127.0.0.1:18088/api/replays/$id); echo "$s" | grep -q running || break; sleep 1; done
echo "$s"
