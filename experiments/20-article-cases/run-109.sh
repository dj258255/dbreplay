#!/bin/sh
# 종 DB 수에 따른 읽기 상한의 비례를 내장 알람으로 다시 잰다(#109). 실험 20의 사례 3은 바깥 알람이 과부하에서 표본을 읽지 못해
# 판정하지 못했다. 여기서는 종 DB마다 도구의 지표에 알람을 건다. 녹화를 읽기 문장만 남겨 두 반쪽으로 나누고,
# 1대는 전체를 재생기 둘로, 2대는 반쪽 하나씩을 종 DB 하나씩에 재생기 하나씩으로 튼다. 재생기 수가 같다. 약 40분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp20_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
MP="jdbc:mysql://mysql-primary:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
R1="jdbc:mysql://mysql-replica:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
R2="jdbc:mysql://mysql-replica2:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
O=out/109
mp() { docker compose exec -T mysql-primary mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
mr() { docker compose exec -T "$1" mysql -h127.0.0.1 -uroot -preplay -e "$2" 2>/dev/null </dev/null; }
sb() { t=$1; shift; docker run --rm --network $NET dbreplay-exp01-sysbench sysbench "$t" --mysql-host=mysql-primary --mysql-user=sb --mysql-password=sb \
  --mysql-db=sbtest --tables=4 --table-size=100000 "$@" </dev/null; }
REPEAT=${REPEAT:-3}; MAX=${MAX:-32}
explore() { name=$1; shift
  start=$(date +%H:%M:%S); t0=$(date +%s)
  $RUN /app/bin/dbreplay explore "$@" --repeat "$REPEAT" --start-speed 1 --max-speed "$MAX" </dev/null > "$O/$name.txt" 2>&1 || true
  echo "$name $t0 $(date +%s)" >> $O/times.txt
  echo "--- $name ($start~$(date +%H:%M:%S)): $(grep -E '^상한: 최소' "$O/$name.txt" || echo '값 없음')"
}

mkdir -p $O; rm -f $O/times.txt
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
for r in mysql-replica mysql-replica2; do mr $r "SET GLOBAL super_read_only = ON"; done

docker rm -f dbreplay-exp20-disk >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp20-disk postgres:16 bash -c 'while true; do a=$EPOCHREALTIME
  dd if=/dev/zero of=/tmp/probe bs=4k count=1 conv=fsync 2>/dev/null; b=$EPOCHREALTIME
  echo "${a%.*} $(( ${b/./} - ${a/./} ))"; sleep 1; done' >/dev/null
trap 'docker logs dbreplay-exp20-disk > $O/disk-probe.txt 2>&1 || true; docker rm -f dbreplay-exp20-disk >/dev/null 2>&1 || true' EXIT

# 녹화: 실험 20과 같다(sysbench oltp_read_only 초당 200, 30초)
mp -e "SET GLOBAL slow_query_log=OFF"; docker compose exec -T mysql-primary rm -f /var/lib/mysql/slow.log </dev/null; mp -e "SET GLOBAL slow_query_log=ON"
sb oltp_read_only --threads=8 --time=30 --rate=200 --report-interval=0 run > "$O/record.txt"
mp -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql-primary cat /var/lib/mysql/slow.log </dev/null > "$O/slow.log"
$RUN /app/bin/dbreplay convert --in /exp/$O/slow.log --out /exp/$O/ro.trace.jsonl </dev/null
rm -f "$O/slow.log"
python3 split.py $O/ro.trace.jsonl $O/reads.trace.jsonl $O/half-a.trace.jsonl $O/half-b.trace.jsonl | tee $O/split.txt

for r in mysql-replica mysql-replica2; do docker update --cpus 1 "dbreplay-exp20-$r-1" >/dev/null; done
explore replicas1 --trace /exp/$O/reads.trace.jsonl --url "$R1" --user root --reset-command true \
  --only reads --workers 2 --alarm-metric "active_sessions>=16"
explore replicas2 --trace /exp/$O/half-a.trace.jsonl --url "$R1" --user root --reset-command true \
  --only reads --replica-trace /exp/$O/half-b.trace.jsonl --replica-url "$R2" \
  --alarm-metric "active_sessions>=16,replica.active_sessions>=16"
docker logs dbreplay-exp20-disk > $O/disk-probe.txt 2>&1
python3 summarize109.py | tee $O/summary.md
echo "끝"
