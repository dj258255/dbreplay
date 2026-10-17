#!/bin/sh
# 바깥 알람 체계 노릇. 라운드 구간(DBREPLAY_ROUND_START~END, 에포크 초)의 기록에서 종 DB 가운데 하나라도 실행 중인 연결이
# 기준(ALARM_SESSIONS, 기본 16) 이상인 표본이 세 번 연속이면 울린다(0이 아닌 값으로 끝난다). 기록은 sample-replicas.sh가 쓴다
awk -v from="$DBREPLAY_ROUND_START" -v to="$DBREPLAY_ROUND_END" -v limit="${ALARM_SESSIONS:-16}" '
  $1 / 1000 >= from && $1 / 1000 <= to {
    if ($3 >= limit) { run[$2]++; if (run[$2] >= 3 && !fired) { fired = 1; why = $2 " 실행 중인 연결 " $3 " (세 번 연속 " limit " 이상)" } }
    else run[$2] = 0
  }
  END { if (fired) { print why; exit 1 } }' /exp/out/sessions.txt
