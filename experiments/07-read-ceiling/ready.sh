#!/bin/sh
# 되돌리기가 끝난 뒤 재생 직전에 불린다 (--ready-command).
# 알람 확인 명령이 이 라운드의 느린 문장만 세도록 서버의 실행 시간 분포를 비운다. 덤프를 되붓는 문장들이 거기 남아 있다.
set -eu
mysql -hmysql84 -uroot -preplay -e "TRUNCATE performance_schema.events_statements_histogram_global" 2>/dev/null
