#!/bin/sh
# 재생기 컨테이너 안에서 돈다. 데이터를 녹화 시작 시점으로 되돌리고 통계를 다시 잡는다(실험 02의 발견 1).
set -eu
MYSQL="mysql -hmysql84 -uroot -preplay sbtest"
$MYSQL < /exp/out/snapshot.sql 2>/dev/null
$MYSQL -e "ANALYZE TABLE sbtest1, sbtest2, sbtest3, sbtest4" >/dev/null 2>&1
# 알람 명령이 이 라운드의 느린 문장만 세도록 서버의 실행 시간 분포를 비운다
$MYSQL -e "TRUNCATE performance_schema.events_statements_histogram_global" >/dev/null 2>&1
