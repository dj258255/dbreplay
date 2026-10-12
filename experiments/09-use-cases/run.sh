#!/bin/sh
# 글이 든 활용 장면 셋을 전후 3번씩 돌린다. 전후는 번갈아 돌린다.
#   A 인덱스 변경: 8.4에서 sbtest1의 k_1을 지운다
#   B 버전 업그레이드: 8.0에서 녹화해 8.0 샌드박스와 8.4 샌드박스에 튼다
#   C 파라미터 변경: 8.4에서 innodb_flush_log_at_trx_commit을 1에서 2로 바꾼다. 이 장면만 상한도 잰다
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp09_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
RUNS="1 2 3"

m() { host=$1; shift; docker compose exec -T "$host" mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
sb() { host=$1; script=$2; shift 2
  docker run --rm --network $NET dbreplay-exp01-sysbench sysbench "$script" --mysql-host="$host" --mysql-user=sb --mysql-password=sb \
    --mysql-db=sbtest --tables=4 --table-size=100000 "$@" </dev/null; }
url() { echo "jdbc:mysql://$1:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"; }

# 켜진 채로 파일을 지우면 서버가 지워진 파일에 계속 쓴다. 먼저 끄고, 지우고, 다시 켠다
log_on() {
  m "$1" -e "SET GLOBAL slow_query_log=OFF"
  docker compose exec -T "$1" rm -f /var/lib/mysql/slow.log </dev/null
  m "$1" -e "SET GLOBAL slow_query_log=ON"
  docker compose exec -T "$1" test -f /var/lib/mysql/slow.log </dev/null
}
# $1 서버, $2 꼬리표, $3 최소 줄 수
log_off() {
  m "$1" -e "SET GLOBAL slow_query_log=OFF"
  docker compose exec -T "$1" cat /var/lib/mysql/slow.log </dev/null > "out/$2-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$2-slow.log" --out "/exp/out/$2.trace.jsonl" </dev/null > "out/$2-convert.txt"
  lines=$(wc -l < "out/$2.trace.jsonl")
  if [ "$lines" -lt "$3" ]; then echo "out/$2.trace.jsonl이 ${lines}줄뿐이다" >&2; exit 1; fi
  rm -f "out/$2-slow.log"
}
prepare() {
  until m "$1" -e "select 1" >/dev/null; do sleep 2; done
  m "$1" -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'"
  docker compose exec -T "$1" mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
  sb "$1" oltp_read_write cleanup >/dev/null 2>&1 || true
  sb "$1" oltp_read_write --threads=4 prepare >/dev/null
  docker compose exec -T "$1" sh -c 'mysqldump -h127.0.0.1 -uroot -preplay --single-transaction sbtest 2>/dev/null' </dev/null > "out/snapshot-$1.sql"
}
# $1 서버, $2 덤프, $3 꼬리표, 나머지는 reset에 줄 옵션. 되돌리고 1배속으로 틀어 그때의 서버 로그를 out/<꼬리표>.trace.jsonl로 남긴다
replay_once() {
  host=$1; snap=$2; tag=$3; shift 3
  $RUN /app/bin/dbreplay reset --url "$(url "$host")" --user root --snapshot "/exp/out/$snap" "$@" </dev/null > "out/$tag-reset.txt"
  log_on "$host"
  $RUN /app/bin/dbreplay replay --trace "/exp/out/$TRACE" --url "$(url "$host")" --user root --speed 1 </dev/null > "out/$tag-replay.txt"
  log_off "$host" "$tag" 20000
  grep -E "오류|실패" "out/$tag-replay.txt" | head -3 || true
}
compare() { $RUN /app/bin/dbreplay compare --before "/exp/out/$1.trace.jsonl" --after "/exp/out/$2.trace.jsonl" --top 5 --rows 60 </dev/null > "out/$3.txt"; }

(cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
m mysql80 -e "select version()" || true
prepare mysql80
prepare mysql84
m mysql84 -e "SET GLOBAL innodb_flush_log_at_trx_commit = 1"

echo "===== 녹화 (8.4): oltp_read_write와 k 인덱스를 타는 읽기를 함께 ====="
log_on mysql84
sb mysql84 select_random_points --threads=2 --time=20 --rate=20 --report-interval=0 run > out/record84-points.txt &
sb mysql84 oltp_read_write --threads=8 --time=20 --rate=100 --report-interval=0 run > out/record84-sysbench.txt
wait
log_off mysql84 prod84 30000
cat out/prod84-convert.txt

echo "===== 녹화 (8.0): oltp_read_write와 양성 대조(QUALIFY를 별칭으로 쓴 문장 120개) ====="
CONTROL=$(awk 'BEGIN { for (i = 1; i <= 120; i++) printf "SELECT id AS qualify FROM sbtest1 WHERE id = %d; ", i }')
# 8.4가 이 문장을 정말 거부하는지 먼저 본다. 받는다면 대조가 성립하지 않는다
if docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay sbtest -e "SELECT id AS qualify FROM sbtest1 WHERE id = 1" </dev/null > out/control-on-84.txt 2>&1; then
  echo "8.4가 대조 문장을 받는다. 대조가 성립하지 않는다"
else
  echo "8.4가 대조 문장을 거부한다: $(grep -v Warning out/control-on-84.txt | head -1)"
fi
log_on mysql80
sb mysql80 oltp_read_write --threads=8 --time=20 --rate=100 --report-interval=0 run > out/record80-sysbench.txt &
sleep 5; m mysql80 sbtest -e "$CONTROL" >/dev/null
wait
log_off mysql80 prod80 30000
cat out/prod80-convert.txt

echo "===== A 인덱스 변경 ====="
TRACE=prod84.trace.jsonl
for i in $RUNS; do
  replay_once mysql84 snapshot-mysql84.sql "a-before-$i"
  replay_once mysql84 snapshot-mysql84.sql "a-after-$i" --change-command "sh /exp/drop-index.sh"
  compare "a-before-$i" "a-after-$i" "a-compare-$i"
  echo "--- A $i"; sed -n '1,12p' "out/a-compare-$i.txt"
done

echo "===== B 버전 업그레이드 (8.0 -> 8.4) ====="
TRACE=prod80.trace.jsonl
for i in $RUNS; do
  replay_once mysql80 snapshot-mysql80.sql "b-before-$i"
  replay_once mysql84 snapshot-mysql80.sql "b-after-$i"
  compare "b-before-$i" "b-after-$i" "b-compare-$i"
  echo "--- B $i"; sed -n '1,3p' "out/b-compare-$i.txt"; sed -n '/오류가 달라진/,$p' "out/b-compare-$i.txt"
done

echo "===== C 파라미터 변경 (innodb_flush_log_at_trx_commit 1 -> 2): 1배속 ====="
TRACE=prod84.trace.jsonl
for i in $RUNS; do
  replay_once mysql84 snapshot-mysql84.sql "c-before-$i" --change-command "sh /exp/flush.sh 1"
  replay_once mysql84 snapshot-mysql84.sql "c-after-$i" --change-command "sh /exp/flush.sh 2"
  compare "c-before-$i" "c-after-$i" "c-compare-$i"
  echo "--- C $i"; sed -n '1,3p' "out/c-compare-$i.txt"; grep -E " COMMIT$" "out/c-compare-$i.txt" | head -1
done

echo "===== C 파라미터 변경: 상한 ====="
for i in $RUNS; do
  for v in 1 2; do
    $RUN /app/bin/dbreplay explore --trace /exp/out/prod84.trace.jsonl --url "$(url mysql84)" --user root \
      --snapshot /exp/out/snapshot-mysql84.sql --change-command "sh /exp/flush.sh $v" --ready-command "sh /exp/ready.sh" \
      --alarm-metric "active_sessions>=32" --alarm-command "sh /exp/alarm-slow.sh" --max-speed 32 </dev/null > "out/c-explore-flush$v-$i.txt"
    echo "--- C 상한 flush=$v $i: $(grep -E '^상한' "out/c-explore-flush$v-$i.txt")"
  done
done
m mysql84 -e "SET GLOBAL innodb_flush_log_at_trx_commit = 1"
rm -f out/*-reset.txt
echo "끝"
