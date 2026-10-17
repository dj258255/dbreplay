#!/bin/sh
# DB와 상관없는 탐침으로 디스크가 느려진 때를 가릴 수 있는지 본다(#99). run.sh에서 io full 압력은 DB 자신의 부하에도 따라 움직여
# 문턱 하나로 무효를 가를 수 없었다. 별도 컨테이너가 4KB를 fsync로 쓰고 걸린 시간을 1초마다 잰다.
# 다: DB 부하 없이 10분. 라: MySQL 부하(run.sh의 가와 같다) 15분. 약 26분. run.sh를 먼저 돌려 DB가 떠 있어야 한다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
NET=dbreplay-exp21_default
SB="docker run --rm --network $NET dbreplay-exp01-sysbench sysbench oltp_read_write
    --mysql-host=mysql84 --mysql-user=sb --mysql-password=sb --mysql-db=sbtest --tables=4 --table-size=100000"
IDLE=${IDLE:-600}; LOAD=${LOAD:-900}
EVENTS=out/probe-events.txt

docker rm -f dbreplay-exp21-psi dbreplay-exp21-probe >/dev/null 2>&1 || true
docker run -d --name dbreplay-exp21-psi alpine:3.20 sh -c 'while true; do
  echo "$(date +%s) io $(tr "\n" " " </proc/pressure/io)cpu $(tr "\n" " " </proc/pressure/cpu)load $(cut -d" " -f1-3 /proc/loadavg)"; sleep 5; done' >/dev/null
# busybox의 date는 나노초를 모른다. bash 5의 EPOCHREALTIME을 쓰려고 postgres 이미지의 bash로 돈다
docker run -d --name dbreplay-exp21-probe postgres:16 bash -c 'while true; do a=$EPOCHREALTIME
  dd if=/dev/zero of=/tmp/probe bs=4k count=1 conv=fsync 2>/dev/null; b=$EPOCHREALTIME
  echo "${a%.*} $(( ${b/./} - ${a/./} ))"; sleep 1; done' >/dev/null
( while docker inspect dbreplay-exp21-psi >/dev/null 2>&1; do echo "$(date +%s) $(sysctl -n vm.loadavg)"; sleep 5; done ) > out/probe-host-load.txt &
: > "$EVENTS"
event() { echo "$(date +%s) $*" >> "$EVENTS"; }

echo "다: 부하 없이 ${IDLE}초"
event "start idle"; sleep "$IDLE"; event "end idle"
echo "라: MySQL ${LOAD}초"
event "start mysql-c"; start=$(date +%s)
$SB --threads=8 --time="$LOAD" --report-interval=1 --percentile=95 run </dev/null > out/mysql-c.txt
event "end mysql-c"; echo "$start" > out/mysql-c.start

docker logs dbreplay-exp21-probe > out/probe.txt 2>&1
docker logs dbreplay-exp21-psi > out/probe-psi.txt 2>&1
docker rm -f dbreplay-exp21-psi dbreplay-exp21-probe >/dev/null
wait
python3 probe_summary.py
echo "끝"
