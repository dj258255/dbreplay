#!/bin/sh
# 1) pgbench 부하를 csvlog로 녹화한다 (확장 프로토콜, 준비된 문장)
# 2) 1배속으로 다시 틀고 그때의 로그를 원본과 비교한다
# 3) synchronous_commit을 끈 뒤 다시 틀어 변경 전후를 비교한다
# 4) 버티는 배속을 탐색한다
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp04_default
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:postgresql://pg16:5432/postgres?preferQueryMode=simple"
CSV=/var/lib/postgresql/data/log/pg.csv

log_on() {
  docker compose exec -T -u postgres pg16 sh -c ": > $CSV" </dev/null
  $P -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
  sleep 1
}
# $1: 꼬리표. 로그를 끄고 out/<꼬리표>.csv로 꺼내 out/<꼬리표>.trace.jsonl로 바꾼다
log_off() {
  $P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
  sleep 1
  docker compose exec -T pg16 cat $CSV </dev/null > "out/$1.csv"
  $RUN /app/bin/dbreplay convert --from postgres-csv --in "/exp/out/$1.csv" --out "/exp/out/$1.trace.jsonl" </dev/null > "out/$1-convert.txt"
  # 파이프 안의 실패는 set -e가 잡지 못한다. 재생이 죽어도 빈 로그로 끝까지 갈 수 있어서 줄 수로 확인한다
  lines=$(wc -l < "out/$1.trace.jsonl")
  if [ "$lines" -lt 30000 ]; then
    echo "out/$1.trace.jsonl이 ${lines}줄뿐이다. 앞 단계가 돌지 않았다" >&2
    exit 1
  fi
}

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done

docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1
docker compose exec -T pg16 pg_dump -U postgres --clean --if-exists shop </dev/null > out/snapshot.sql

echo "== 녹화 =="
log_on
$RUN /app/bin/dbreplay sample --url "$URL" --user postgres --seconds 30 --metric-command "sh /exp/metrics.sh" </dev/null > out/record-metrics.txt &
docker compose exec -T pg16 pgbench -U postgres -n -c 8 -j 4 -T 30 -R 200 -M prepared shop </dev/null > out/record-pgbench.txt 2>&1
wait
test -s out/record-metrics.txt
log_off prod
cat out/prod-convert.txt

echo "== 변경 전: 1배속 재생 =="
$RUN sh /exp/reset.sh </dev/null
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user postgres --speed 1 --metric-command "sh /exp/metrics.sh" </dev/null | tee out/replay-before.txt
log_off before

echo "== 변경 후(synchronous_commit=off): 1배속 재생 =="
$RUN sh /exp/reset.sh </dev/null
$P -c "ALTER SYSTEM SET synchronous_commit = off" -c "SELECT pg_reload_conf()" </dev/null >/dev/null
log_on
$RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user postgres --speed 1 --metric-command "sh /exp/metrics.sh" </dev/null | tee out/replay-after.txt
log_off after
$P -c "ALTER SYSTEM RESET synchronous_commit" -c "SELECT pg_reload_conf()" </dev/null >/dev/null

echo "== 비교: 원본 대 변경 전 재생 (재생이 원본과 같은가) =="
$RUN /app/bin/dbreplay compare --before /exp/out/prod.trace.jsonl --after /exp/out/before.trace.jsonl </dev/null | tee out/compare-prod-vs-before.txt
echo "== 비교: 변경 전 대 변경 후 =="
$RUN /app/bin/dbreplay compare --before /exp/out/before.trace.jsonl --after /exp/out/after.trace.jsonl </dev/null | tee out/compare-before-vs-after.txt

echo "== 용량 탐색 =="
$RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user postgres \
  --reset-command "sh /exp/reset.sh" --alarm-metric "active_sessions>=32" --metric-command "sh /exp/metrics.sh" --max-speed 64 \
  --out /exp/out/evaluation.json </dev/null | tee out/explore.txt

echo "== 평가 유효성 확인 =="
$RUN /app/bin/dbreplay check --evaluation /exp/out/evaluation.json --url "$URL" --user postgres \
  --alarm-metric "active_sessions>=32" --trace /exp/out/prod.trace.jsonl </dev/null | tee out/check-valid.txt
