#!/bin/sh
# 파라미터 장면의 상한만 다시 잰다. run.sh의 녹화(20초, 초당 100 트랜잭션)는 가벼워서 상한이 한도(32배속)에 붙었다.
# 30초, 초당 200 트랜잭션으로 다시 녹화해 innodb_flush_log_at_trx_commit 1과 2에서 3번씩 탐색한다.
# run.sh가 띄운 서버와 out/snapshot-mysql84.sql을 그대로 쓴다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
NET=dbreplay-exp09_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
URL="jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
m() { docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }

$RUN /app/bin/dbreplay reset --url "$URL" --user root --snapshot /exp/out/snapshot-mysql84.sql --change-command "sh /exp/flush.sh 1" </dev/null >/dev/null
m -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
m -e "SET GLOBAL slow_query_log=ON"
docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb \
  --mysql-db=sbtest --tables=4 --table-size=100000 --threads=8 --time=30 --rate=200 --report-interval=0 run </dev/null > out/record84-heavy-sysbench.txt
m -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/heavy-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/heavy-slow.log --out /exp/out/prod84-heavy.trace.jsonl </dev/null | tee out/prod84-heavy-convert.txt
test "$(wc -l < out/prod84-heavy.trace.jsonl)" -ge 100000
rm -f out/heavy-slow.log

for i in 1 2 3; do
  for v in 1 2; do
    $RUN /app/bin/dbreplay explore --trace /exp/out/prod84-heavy.trace.jsonl --url "$URL" --user root \
      --snapshot /exp/out/snapshot-mysql84.sql --change-command "sh /exp/flush.sh $v" --ready-command "sh /exp/ready.sh" \
      --alarm-metric "active_sessions>=32" --alarm-command "sh /exp/alarm-slow.sh" --max-speed 32 </dev/null > "out/c-heavy-explore-flush$v-$i.txt"
    echo "--- C 상한(무거운 녹화) flush=$v $i: $(grep -E '^상한' "out/c-heavy-explore-flush$v-$i.txt")"
  done
done
m -e "SET GLOBAL innodb_flush_log_at_trx_commit = 1"
echo "끝"
