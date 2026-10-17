#!/bin/sh
# 1부만 다시 돈다. 첫 실행에서는 부하가 끝나자마자 캡처를 멈춰 연결의 마지막 패킷이 파일에 쓰이지 않았다(README).
# 캡처를 멈추기 전에 3초 기다리고, 멈출 때 tcpdump가 센 받은 패킷과 놓친 패킷을 남긴다. 캡처 파일은 컨테이너 안에 쓰고
# 멈춘 뒤 꺼낸다. 호스트 폴더(도커의 virtiofs)에 바로 쓰면 tcpdump가 따라가지 못해 받은 패킷의 28%를 버렸다(README).
# 패킷 캡처로 녹화한다(#79). 1부: 서버 로그와 패킷 캡처를 동시에 떠서 변환한 문장을 맞춰 본다(sysbench는 준비한 문장,
# pgbench는 -M prepared로 확장 질의를 쓴다). 2부: 캡처의 부담을 실험 15와 같은 방식으로 잰다(속도 제한 없이 8개 연결로 30초,
# 끔과 켬을 번갈아 5번). 캡처는 DB 컨테이너의 네트워크에 붙은 별도 컨테이너의 tcpdump가 한다. 약 15분.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"
NET=dbreplay-exp19_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp dbreplay-exp-runner"
CSV=/var/lib/postgresql/data/log/pg.csv
RUNS=${RUNS:-"1 2 3 4 5"}

# $1 DB 컨테이너, $2 포트, $3 파일 이름. 캡처를 켠다
capture_on() { docker rm -f dbreplay-exp19-tcpdump >/dev/null 2>&1 || true; docker run -d --name dbreplay-exp19-tcpdump --net container:dbreplay-exp19-$1-1 dbreplay-exp19-tcpdump \
  tcpdump -i eth0 -s 0 -B 65536 -w "/tmp/$3.pcap" "tcp port $2" >/dev/null; CAPTURE=$3; sleep 2; }
capture_off() { sleep 3; docker kill -s INT dbreplay-exp19-tcpdump >/dev/null 2>&1 || true; docker wait dbreplay-exp19-tcpdump >/dev/null 2>&1 || true
  docker logs dbreplay-exp19-tcpdump 2>&1 | grep -E "packets (captured|received|dropped)" | tr '\n' ' '; echo
  docker cp "dbreplay-exp19-tcpdump:/tmp/$CAPTURE.pcap" "out/$CAPTURE.pcap" >/dev/null; docker rm dbreplay-exp19-tcpdump >/dev/null 2>&1 || true; }

docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker build -q -t dbreplay-exp-runner ../runner >/dev/null
docker build -q -t dbreplay-exp19-tcpdump tcpdump >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB cleanup >/dev/null 2>&1 </dev/null || true
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1

echo "===== 1부: 같은 부하를 서버 로그와 패킷 캡처로 동시에 ====="
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null; docker compose exec -T mysql84 rm -f /var/lib/mysql/slow.log </dev/null
capture_on mysql84 3306 mysql-fidelity
$M -e "SET GLOBAL slow_query_log=ON" 2>/dev/null </dev/null
$SB --threads=4 --time=10 --rate=100 --report-interval=0 run </dev/null > out/mysql-fidelity-sysbench.txt
$M -e "SET GLOBAL slow_query_log=OFF" 2>/dev/null </dev/null
capture_off
docker compose exec -T mysql84 cat /var/lib/mysql/slow.log </dev/null > out/mysql-fidelity-slow.log
$RUN /app/bin/dbreplay convert --in /exp/out/mysql-fidelity-slow.log --out /exp/out/mysql-log.trace.jsonl </dev/null | tee out/mysql-convert-log.txt
$RUN /app/bin/dbreplay convert --from mysql-pcap --in /exp/out/mysql-fidelity.pcap --out /exp/out/mysql-pcap.trace.jsonl </dev/null | tee out/mysql-convert-pcap.txt
python3 compare.py out/mysql-log.trace.jsonl out/mysql-pcap.trace.jsonl | tee out/mysql-compare.txt

docker compose exec -T -u postgres pg16 sh -c ": > $CSV" </dev/null
capture_on pg16 5432 pg-fidelity
$P -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" </dev/null >/dev/null; sleep 1
# 컨테이너 안에서 걸면 루프백으로 붙어 eth0에서 보이지 않는다. 같은 네트워크의 다른 컨테이너에서 건다
docker run --rm --network $NET -e PGPASSWORD=replay postgres:16 pgbench -h pg16 -U postgres -n -c 4 -j 2 -T 10 -R 100 -M prepared shop </dev/null > out/pg-fidelity-pgbench.txt 2>&1 || true
$P -c "ALTER SYSTEM SET log_min_duration_statement = -1" -c "SELECT pg_reload_conf()" </dev/null >/dev/null; sleep 1
capture_off
docker compose exec -T pg16 cat $CSV </dev/null > out/pg-fidelity.csv
$RUN /app/bin/dbreplay convert --from postgres-csv --in /exp/out/pg-fidelity.csv --out /exp/out/pg-log.trace.jsonl </dev/null | tee out/pg-convert-log.txt
$RUN /app/bin/dbreplay convert --from postgres-pcap --in /exp/out/pg-fidelity.pcap --out /exp/out/pg-pcap.trace.jsonl </dev/null | tee out/pg-convert-pcap.txt
python3 compare.py out/pg-log.trace.jsonl out/pg-pcap.trace.jsonl | tee out/pg-compare.txt

echo "끝"
