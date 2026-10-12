#!/bin/sh
# PostgreSQL에서 되돌린 뒤 체크포인트를 일으키면 무엇이 달라지는지 본다.
# 실험 04를 돌린 직후에, 그 실험의 컨테이너와 녹화 파일을 그대로 써서 돈다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
EXP04="$ROOT/experiments/04-postgres"
cd "$EXP04"
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
RUN="docker run --rm --network dbreplay-exp04_default -v $ROOT/build/install/dbreplay:/app:ro -v $EXP04:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:postgresql://pg16:5432/postgres?preferQueryMode=simple"
OUT="$HERE/out/pg-checkpoint.txt"
: > "$OUT"

# 되돌린 뒤 1배속으로 한 번 틀고, 그동안 서버가 쓴 WAL과 전체 페이지 이미지 수를 잰다
one_replay() {
  $RUN /app/bin/dbreplay reset --url "$URL" --user postgres --snapshot /exp/out/snapshot.sql --snapshot-db shop </dev/null >/dev/null
  if [ "$1" = checkpoint ]; then $P -c "CHECKPOINT" </dev/null >/dev/null; fi
  $P -c "SELECT pg_stat_reset_shared('wal')" </dev/null >/dev/null
  replay=$($RUN /app/bin/dbreplay replay --trace /exp/out/prod.trace.jsonl --url "$URL" --user postgres --speed 1 </dev/null)
  wal=$($P -c "SELECT wal_fpi || ' ' || round(wal_bytes / 1048576.0, 1) FROM pg_stat_wal" </dev/null)
  echo "$1 $wal $(echo "$replay" | awk '/실행 시간 중앙값/ {print $6} /실행 시간 p99/ {print $6}' | tr '\n' ' ')"
}

echo "조건 전체페이지이미지 WAL(MB) 재생_중앙값 재생_p99" | tee -a "$OUT"
for i in 1 2; do
  for mode in plain checkpoint; do one_replay $mode | tee -a "$OUT"; done
done

for mode in plain checkpoint; do
  echo "== 용량 탐색: $mode ==" | tee -a "$OUT"
  ready=""
  if [ "$mode" = checkpoint ]; then ready="PGPASSWORD=replay psql -h pg16 -U postgres -d shop -q -c CHECKPOINT"; fi
  $RUN /app/bin/dbreplay explore --trace /exp/out/prod.trace.jsonl --url "$URL" --user postgres \
    --snapshot /exp/out/snapshot.sql --snapshot-db shop ${ready:+--ready-command "$ready"} \
    --alarm-metric "active_sessions>=32" --max-speed 32 </dev/null | grep -E "^[0-9]+ |^상한" | cut -c1-150 | tee -a "$OUT"
done
