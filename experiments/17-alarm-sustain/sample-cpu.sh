#!/bin/sh
# 도커 가상 머신을 뺀 프로세스의 CPU 합을 30초마다 남긴다(100 = 코어 하나). 맥의 부하 평균은 도커 가상 머신 안의
# 스레드까지 세서 실험 자체의 부하와 바깥의 방해를 가르지 못한다. run.sh가 끝나면 멈춘다.
cd "$(dirname "$0")"
while pgrep -f "run.sh" >/dev/null; do
  echo "$(date +%H:%M:%S) $(ps -Ao pcpu,comm | awk 'NR>1 && $0 !~ /Virtualization|com.docker|vpnkit/ {s+=$1} END {printf "%.0f", s}')" >> out/cpu.txt
  sleep 30
done
