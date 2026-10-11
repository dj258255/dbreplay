#!/bin/sh
# 지표 명령의 예. 재생기 컨테이너 안에서 돈다.
# DB 접속으로는 못 읽는 Load Average와 CPU busy를 낸다. 도커의 컨테이너들은 커널을 함께 써서
# 여기서 읽는 값에 DB 컨테이너의 부하가 들어 있다. 재생기 자신의 CPU도 함께 들어 있다.
read load1 rest < /proc/loadavg
echo "load1 $load1"
before=$(awk '/^cpu /{print $2+$3+$4+$7+$8, $5+$6}' /proc/stat)
sleep 0.2
after=$(awk '/^cpu /{print $2+$3+$4+$7+$8, $5+$6}' /proc/stat)
echo "$before $after" | awk '{busy=$3-$1; idle=$4-$2; printf "cpu_busy %.1f\n", (busy+idle > 0) ? 100*busy/(busy+idle) : 0}'
