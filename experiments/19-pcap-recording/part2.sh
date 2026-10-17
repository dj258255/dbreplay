#!/bin/sh
# 2부만 다시 돈다. 캡처를 컨테이너 안에 쓰는 설정(part1.sh와 같다)으로 부담을 다시 잰다. 캡처 파일은 꺼내지 않고,
# 끔과 켬 모두 시작 전에 20초 쉰다.
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
capture_off() { sleep 3; BYTES=$(docker exec dbreplay-exp19-tcpdump stat -c %s "/tmp/$CAPTURE.pcap" 2>/dev/null || echo 0); docker kill -s INT dbreplay-exp19-tcpdump >/dev/null 2>&1 || true; docker wait dbreplay-exp19-tcpdump >/dev/null 2>&1 || true
  docker logs dbreplay-exp19-tcpdump 2>&1 | grep -E "packets (captured|received|dropped)" | tr '\n' ' '; echo
  # 부담을 잴 때는 파일을 꺼내지 않는다. 1GB 넘는 파일을 꺼내는 디스크 쓰기가 다음 "끔" 실행에 얹혔다(README). 크기는 멈추기 직전에 잰다
  docker rm dbreplay-exp19-tcpdump >/dev/null 2>&1 || true; }

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

echo "===== 2부: 캡처의 부담 ====="
$SB --threads=8 --time=20 --report-interval=0 run </dev/null >/dev/null
for i in $RUNS; do
  for mode in off on; do
    [ "$mode" = on ] && capture_on mysql84 3306 mysql-overhead
    sleep 20  # 앞 실행의 디스크 쓰기가 가라앉게 끔과 켬 모두 쉰다
    $SB --threads=8 --time=30 --report-interval=0 --percentile=95 run </dev/null > "out/mysql2-$mode-$i.txt"
    bytes=0; if [ "$mode" = on ]; then capture_off; bytes=$BYTES; fi
    echo "mysql $mode $i tps=$(awk '/transactions:/ {gsub(/[()]/, "", $3); print $3}' "out/mysql2-$mode-$i.txt") p95_ms=$(awk '/95th percentile:/ {print $3}' "out/mysql2-$mode-$i.txt") capture_bytes=$bytes"
  done
done
# pgbench는 컨테이너 안에서 돌면 루프백으로 붙어 eth0에서 보이지 않는다. 같은 네트워크의 다른 컨테이너에서 건다
PGB="docker run --rm --network $NET -e PGPASSWORD=replay postgres:16 pgbench -h pg16 -U postgres -n -c 8 -j 4 -T 30 -M prepared shop"
docker run --rm --network $NET -e PGPASSWORD=replay postgres:16 pgbench -h pg16 -U postgres -n -c 8 -j 4 -T 20 -M prepared shop </dev/null >/dev/null 2>&1
for i in $RUNS; do
  for mode in off on; do
    [ "$mode" = on ] && capture_on pg16 5432 pg-overhead
    sleep 20  # 앞 실행의 디스크 쓰기가 가라앉게 끔과 켬 모두 쉰다
    $PGB </dev/null > "out/pg2-$mode-$i.txt" 2>&1
    bytes=0; if [ "$mode" = on ]; then capture_off; bytes=$BYTES; fi
    echo "pg $mode $i tps=$(awk '/^tps = / {print $3}' "out/pg2-$mode-$i.txt") latency_avg_ms=$(awk '/^latency average = / {print $4}' "out/pg2-$mode-$i.txt") capture_bytes=$bytes"
  done
done
echo "끝"
