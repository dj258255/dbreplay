#!/bin/sh
# 이 실험의 것이 아닌 컨테이너의 CPU 합을 30초마다 남긴다(100 = 코어 하나). sample-cpu.sh는 도커 가상 머신을 통째로 빼서
# 그 안에서 도는 다른 프로젝트의 컨테이너를 놓친다. 이 실험의 것: exp17 DB, 재생기(dbreplay-exp-runner), sysbench
cd "$(dirname "$0")"
while pgrep -f "run.sh" >/dev/null; do
  mine=$(docker ps --format '{{.Names}} {{.Image}}' | awk '$1 ~ /^dbreplay-exp17/ || $2 ~ /dbreplay-exp-runner|dbreplay-exp01-sysbench/ {print $1}')
  other=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' | while read name cpu; do
    echo "$mine" | grep -qx "$name" || echo "${cpu%\%}"; done | awk '{s+=$1} END {printf "%.0f", s}')
  echo "$(date +%H:%M:%S) $other" >> out/docker-other.txt
  sleep 30
done
