#!/bin/sh
# 글의 사례를 도커 자원 제한(--cpus)으로 따라 한다(#89). 자원 제한을 사양으로 본다. 서버가 따로인 운영과 같지 않다.
#   1 사양 축소: 주 DB의 CPU 4와 2에서 상한을 --repeat 3. 요구 배수 2를 두고 줄여도 되는지 본다(원문 대조 45)
#   2 서버 비교: CPU 2에서 버퍼 풀 128MB와 32MB를 같은 절차로(원문 대조 46a)
#   3 확장의 비례: 종 DB 1대와 2대에 읽기를 나눠 걸어 읽기만의 상한을 --repeat 3(원문 대조 53a, 57, 권고의 가정 #7)
# 알람: 1, 2는 내장(active_sessions>=32), 3은 바깥 알람 체계 노릇을 하는 명령(alarm-replicas.sh). 약 1시간 반.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp20_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
MP="jdbc:mysql://mysql-primary:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
# 종 DB에 나눠 걸 때 드라이버의 loadbalance를 쓴다. 기본은 연결을 맺을 때 한 번만 호스트를 고르고 자동 커밋에서는 다시 고르지 않아,
# 무거운 연결 8개가 한쪽으로 몰렸다(시험 실행에서 79,241 대 5,572). 문장마다 다시 고르게 하자 42,573 대 42,740이었다.
# 1대도 같은 드라이버 경로로 건다
LB="sslMode=DISABLED&allowPublicKeyRetrieval=true&loadBalanceAutoCommitStatementThreshold=1"
MR1="jdbc:mysql:loadbalance://mysql-replica:3306/sbtest?$LB"
MR2="jdbc:mysql:loadbalance://mysql-replica:3306,mysql-replica2:3306/sbtest?$LB"
mp() { docker compose exec -T mysql-primary mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
mr() { docker compose exec -T "$1" mysql -h127.0.0.1 -uroot -preplay -e "$2" 2>/dev/null </dev/null; }
# 시험 이름을 한 번만 넘긴다. 10-15에 써 둔 것은 "$1" 뒤에 "$@"를 붙여 이름이 두 번 갔다(돌리기 전 검토에서 찾음)
sb() { t=$1; shift; docker run --rm --network $NET dbreplay-exp01-sysbench sysbench "$t" --mysql-host=mysql-primary --mysql-user=sb --mysql-password=sb \
  --mysql-db=sbtest --tables=4 --table-size=100000 "$@" </dev/null; }
cpus() { docker update --cpus "$2" "dbreplay-exp20-$1-1" >/dev/null; }
REPEAT=${REPEAT:-3}; MAX=${MAX:-32}
explore() { name=$1; shift
  start=$(date +%H:%M:%S); t0=$(date +%s)
  $RUN /app/bin/dbreplay explore "$@" --repeat "$REPEAT" --start-speed 1 --max-speed "$MAX" </dev/null > "out/$name.txt" 2>&1 || true
  echo "$name $t0 $(date +%s)" >> out/times.txt
  echo "--- $name ($start~$(date +%H:%M:%S)): $(grep -E '^상한: 최소' "out/$name.txt" || echo '값 없음')"
}
# 버퍼 풀은 시작할 때 정한다. SET GLOBAL로 줄이면 청크 크기(128MB)의 배수로 올라가 32MB가 되지 않는다.
# 두 크기 모두 다시 띄워 조건을 맞춘다. 종 DB는 다시 붙는다
pool() {
  mp -e "SET PERSIST_ONLY innodb_buffer_pool_chunk_size = $1; SET PERSIST_ONLY innodb_buffer_pool_size = $1"
  docker compose restart mysql-primary >/dev/null 2>&1
  until mp -e "select 1" >/dev/null; do sleep 2; done
  echo "버퍼 풀 $(mp -N -e 'SELECT @@innodb_buffer_pool_size')"
}

[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew -q installDist)
docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d --wait >/dev/null 2>&1 || true
until mp -e "select 1" >/dev/null && mr mysql-replica "select 1" >/dev/null && mr mysql-replica2 "select 1" >/dev/null; do sleep 2; done
mp -e "RESET BINARY LOGS AND GTIDS"
for r in mysql-replica mysql-replica2; do mr $r "RESET BINARY LOGS AND GTIDS"; done
mp -e "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%'"
for r in mysql-replica mysql-replica2; do
  mr $r "CHANGE REPLICATION SOURCE TO SOURCE_HOST='mysql-primary', SOURCE_USER='repl', SOURCE_PASSWORD='repl', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA"
done
mp -e "CREATE USER 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'"
sleep 3
docker compose exec -T mysql-primary mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
sb oltp_read_write --threads=4 prepare >/dev/null
for r in mysql-replica mysql-replica2; do
  docker compose exec -T $r mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
  mr $r "SET GLOBAL super_read_only = ON"
done

# 디스크 탐침(실험 21, #99). 무너진 실행을 가린다. 판정은 summarize.py
rm -f out/times.txt
docker rm -f dbreplay-exp20-disk >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp20-disk postgres:16 bash -c 'while true; do a=$EPOCHREALTIME
  dd if=/dev/zero of=/tmp/probe bs=4k count=1 conv=fsync 2>/dev/null; b=$EPOCHREALTIME
  echo "${a%.*} $(( ${b/./} - ${a/./} ))"; sleep 1; done' >/dev/null
trap 'docker logs dbreplay-exp20-disk > out/disk-probe.txt 2>&1 || true; docker rm -f dbreplay-exp20-disk dbreplay-exp20-sampler >/dev/null 2>&1 || true' EXIT

# 녹화: 주 DB의 읽기와 쓰기 30초, 읽기만 30초. 스냅샷도 뜬다
$RUN /app/bin/dbreplay snapshot --url "$MP" --user root --out /exp/out/base.sql </dev/null
for kind in oltp_read_write oltp_read_only; do
  mp -e "SET GLOBAL slow_query_log=OFF"; docker compose exec -T mysql-primary rm -f /var/lib/mysql/slow.log </dev/null; mp -e "SET GLOBAL slow_query_log=ON"
  sb $kind --threads=8 --time=30 --rate=200 --report-interval=0 run > "out/record-$kind.txt"
  mp -e "SET GLOBAL slow_query_log=OFF"
  docker compose exec -T mysql-primary cat /var/lib/mysql/slow.log </dev/null > "out/$kind-slow.log"
  $RUN /app/bin/dbreplay convert --in "/exp/out/$kind-slow.log" --out "/exp/out/$kind.trace.jsonl" </dev/null
  rm -f "out/$kind-slow.log"
done

echo "===== 1 사양 축소 (주 DB CPU 4 → 2, 요구 2배) ====="
for c in 4 2; do
  cpus mysql-primary $c
  explore "1-cpu$c" --trace /exp/out/oltp_read_write.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
    --alarm-metric "active_sessions>=32"
done

echo "===== 2 서버 비교 (CPU 2, 버퍼 풀 128MB와 32MB) ====="
cpus mysql-primary 2
for pool in 134217728 33554432; do
  pool $pool
  explore "2-pool$pool" --trace /exp/out/oltp_read_write.trace.jsonl --url "$MP" --user root --snapshot /exp/out/base.sql \
    --alarm-metric "active_sessions>=32"
done
pool 134217728
cpus mysql-primary 4

echo "===== 3 확장의 비례 (종 DB CPU 1씩, 읽기만, 1대와 2대) ====="
for r in mysql-replica mysql-replica2; do cpus $r 1; done
rm -f out/sessions.txt
docker run -d --name dbreplay-exp20-sampler --network $NET -v $HERE:/exp --entrypoint sh dbreplay-exp-runner /exp/sample-replicas.sh >/dev/null
sleep 2
for n in 1 2; do
  url=$MR1; [ $n = 2 ] && url=$MR2
  explore "3-replicas$n" --trace /exp/out/oltp_read_only.trace.jsonl --url "$url" --user root --reset-command true \
    --only reads --workers 2 --alarm-command "sh /exp/alarm-replicas.sh"
done
docker rm -f dbreplay-exp20-sampler >/dev/null
docker logs dbreplay-exp20-disk > out/disk-probe.txt 2>&1
python3 summarize.py | tee out/summary.md
echo "끝"
