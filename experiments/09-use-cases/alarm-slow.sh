#!/bin/sh
# 알람 확인 명령의 예. 재생기 컨테이너 안에서 돈다.
# 이번 라운드에 100ms 넘게 걸린 문장이 50개 이상이면 알람이 울린 것으로 본다.
# 실제 운영에서는 이 자리에 쓰고 있는 알람 체계에 그 구간의 알람을 묻는 명령을 넣는다.
set -eu
slow=$(mysql -hmysql84 -uroot -preplay -N -e "SELECT COALESCE(SUM(COUNT_BUCKET), 0)
  FROM performance_schema.events_statements_histogram_global WHERE BUCKET_TIMER_LOW >= 100000000000" 2>/dev/null)
if [ "$slow" -ge 50 ]; then
  echo "100ms 넘는 문장 ${slow}개"
  exit 1
fi
