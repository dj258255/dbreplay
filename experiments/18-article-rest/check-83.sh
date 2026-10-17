#!/bin/sh
# #83 변환할 때 대상 DB와 계정만 걸러 낸다. 주 DB에 데이터베이스 둘(sbtest, other)로 부하를 함께 걸고 녹화한 뒤
# --database sbtest와 --exclude-user로 걸러, 남은 문장이 전부 그 데이터베이스의 것인지 본다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
mp -e "CREATE DATABASE IF NOT EXISTS other; CREATE TABLE IF NOT EXISTS other.t (id INT PRIMARY KEY, v INT); REPLACE INTO other.t VALUES (1, 1)"
mp -e "CREATE USER IF NOT EXISTS 'batch'@'%' IDENTIFIED BY 'batch'; GRANT ALL ON other.* TO 'batch'@'%'"
mp -e "SET GLOBAL slow_query_log=OFF"; docker compose exec -T mysql-primary rm -f /var/lib/mysql/slow.log </dev/null; mp -e "SET GLOBAL slow_query_log=ON"
( i=0; while [ $i -lt 200 ]; do echo "SELECT v FROM t WHERE id = 1; UPDATE t SET v = v + 1 WHERE id = 1;"; i=$((i+1)); done ) \
  | docker compose exec -T mysql-primary mysql -h127.0.0.1 -ubatch -pbatch --get-server-public-key other >/dev/null 2>&1 &
sb mysql-primary oltp_read_write --threads=4 --time=5 --rate=100 --report-interval=0 run >/dev/null
wait
mp -e "SET GLOBAL slow_query_log=OFF"
docker compose exec -T mysql-primary cat /var/lib/mysql/slow.log </dev/null > out/83-slow.log
echo "== 거르지 않음"
$RUN /app/bin/dbreplay convert --in /exp/out/83-slow.log --out /exp/out/83-all.trace.jsonl </dev/null
echo "== --database sbtest"
$RUN /app/bin/dbreplay convert --in /exp/out/83-slow.log --out /exp/out/83-sbtest.trace.jsonl --database sbtest </dev/null
echo "== --exclude-user sb"
$RUN /app/bin/dbreplay convert --in /exp/out/83-slow.log --out /exp/out/83-nosb.trace.jsonl --exclude-user sb </dev/null
python3 - <<'PY'
import json
for name in ["83-all", "83-sbtest", "83-nosb"]:
    lines = open(f"out/{name}.trace.jsonl").read().splitlines()
    head = json.loads(lines[0]); recs = [json.loads(l) for l in lines[1:]]
    dbs = {}
    for r in recs:
        if r.get("sql"):
            dbs[r.get("db")] = dbs.get(r.get("db"), 0) + 1
    print(f"{name}: 머리말 scope={head.get('scope')}, 데이터베이스별 문장 수 {dbs}")
PY
rm -f out/83-slow.log
