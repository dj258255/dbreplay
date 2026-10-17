#!/bin/sh
# 측정 도중 장비가 무너지는 때가 입출력 압력과 겹치는지 본다(#99). 실험 17과 19 2부에서 DB 처리량이 측정 조건과 상관없이
# 절반 아래로 떨어졌다가 돌아왔다. 도커 가상 머신의 입출력 압력(PSI)을 5초마다 남기면서 속도 제한 없는 부하를 오래 건다.
# 가: 그냥 둔다. 나: 같은 부하에 2GB 파일을 쓰고 20초 뒤 지우는 일을 3분마다 일으킨다(캡처 파일과 바이너리 로그를 지우는 일을 흉내 낸다).
# 약 50분. 판정 기준은 재기 전에 #99에 적었다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
NET=dbreplay-exp21_default
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"
P="docker compose exec -T pg16 psql -U postgres -d shop -q -t -A"
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
PGB="docker run --rm --network $NET -e PGPASSWORD=replay postgres:16 pgbench -h pg16 -U postgres -n -c 8 -j 4 -M prepared"
MY_A=${MY_A:-900}; MY_B=${MY_B:-600}; PG_A=${PG_A:-600}; PG_B=${PG_B:-600}; TIMES=${TIMES:-3}
EVENTS=out/events.txt

docker build -q -t dbreplay-exp01-sysbench ../01-log-feasibility/bench >/dev/null
docker compose up -d --wait >/dev/null 2>&1 || true
until $M -e "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
until $P -c "select 1" >/dev/null 2>&1 </dev/null; do sleep 2; done
$M -e "CREATE USER IF NOT EXISTS 'sb'@'%' IDENTIFIED BY 'sb'; GRANT ALL ON sbtest.* TO 'sb'@'%'" 2>/dev/null </dev/null
docker compose exec -T mysql84 mysql -h127.0.0.1 -usb -psb --get-server-public-key -e "select 1" >/dev/null 2>&1 </dev/null
$SB cleanup >/dev/null 2>&1 </dev/null || true
$SB --threads=4 prepare >/dev/null </dev/null
docker compose exec -T pg16 pgbench -U postgres -i -s 5 -q shop </dev/null >/dev/null 2>&1

# 기록기. /proc/pressure는 이름공간으로 나뉘지 않아 어느 컨테이너에서 읽어도 가상 머신 전체의 값이다
docker rm -f dbreplay-exp21-psi >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp21-psi alpine:3.20 sh -c 'while true; do
  echo "$(date +%s) io $(tr "\n" " " </proc/pressure/io)cpu $(tr "\n" " " </proc/pressure/cpu)load $(cut -d" " -f1-3 /proc/loadavg)"; sleep 5; done' >/dev/null
# 맥의 부하 평균. 실험 16에서 장비의 다른 일 탓으로 적은 값이다
( while docker inspect dbreplay-exp21-psi >/dev/null 2>&1; do echo "$(date +%s) $(sysctl -n vm.loadavg)"; sleep 5; done ) > out/host-load.txt &
: > "$EVENTS"
event() { echo "$(date +%s) $*" >> "$EVENTS"; }

# $1 컨테이너, $2 그 안의 디렉터리, $3 처음 기다릴 초, $4 횟수. 2GB를 fsync로 쓰고 20초 뒤 지운다. 3분 간격
provoke() {
  sleep "$3"; n=1
  while [ "$n" -le "$4" ]; do
    event "write-start $1"; docker exec "$1" sh -c "dd if=/dev/zero of=$2/exp21-big bs=1M count=2048 conv=fsync 2>/dev/null"; event "write-end $1"
    sleep 20
    event "delete-start $1"; docker exec "$1" rm -f "$2/exp21-big"; event "delete-end $1"
    # `[ ] && sleep`으로 쓰면 마지막 반복에서 1을 돌려 set -e가 스크립트를 멈춘다
    n=$((n + 1)); if [ "$n" -le "$4" ]; then sleep 140; fi
  done
}

# sysbench의 경과 초에 시작 시각을 더해 시각을 붙인다
mysql_run() {
  event "start $1"; start=$(date +%s)
  $SB --threads=8 --time="$2" --report-interval=1 --percentile=95 run </dev/null > "out/$1.txt"
  event "end $1"; echo "$start" > "out/$1.start"
}

echo "가: MySQL ${MY_A}초"
mysql_run mysql-a "$MY_A"
sleep 20
echo "나: MySQL ${MY_B}초, 파일 쓰고 지우기"
provoke dbreplay-exp21-mysql84-1 /var/lib/mysql 60 "$TIMES" &
mysql_run mysql-b "$MY_B"
wait $!
docker compose exec -T mysql84 sh -c 'ls -l --time-style=+%s /var/lib/mysql/binlog.*' > out/mysql-binlogs.txt </dev/null || true
sleep 20

echo "가: PostgreSQL ${PG_A}초"
event "start pg-a"; $PGB -T "$PG_A" -P 1 --progress-timestamp shop </dev/null > out/pg-a.txt 2>&1; event "end pg-a"
sleep 20
echo "나: PostgreSQL ${PG_B}초, 파일 쓰고 지우기"
provoke dbreplay-exp21-pg16-1 /var/lib/postgresql/data 60 "$TIMES" &
event "start pg-b"; $PGB -T "$PG_B" -P 1 --progress-timestamp shop </dev/null > out/pg-b.txt 2>&1; event "end pg-b"
wait $!

docker logs dbreplay-exp21-psi > out/psi.txt 2>&1
docker rm -f dbreplay-exp21-psi >/dev/null
wait
python3 summarize.py
echo "끝"
