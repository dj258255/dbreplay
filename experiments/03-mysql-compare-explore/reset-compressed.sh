#!/bin/sh
# 되돌린 뒤 "변경"을 넣는다. 메이투안 글의 사례와 같은 테이블 압축이다.
set -eu
MYSQL="mysql -hmysql84 -uroot -preplay sbtest"
$MYSQL < /exp/out/snapshot.sql 2>/dev/null
for t in sbtest1 sbtest2 sbtest3 sbtest4; do
  $MYSQL -e "ALTER TABLE $t ROW_FORMAT=COMPRESSED KEY_BLOCK_SIZE=8" 2>/dev/null
done
$MYSQL -e "ANALYZE TABLE sbtest1, sbtest2, sbtest3, sbtest4" >/dev/null 2>&1
# 알람 명령이 이 라운드의 느린 문장만 세도록 서버의 실행 시간 분포를 비운다
$MYSQL -e "TRUNCATE performance_schema.events_statements_histogram_global" >/dev/null 2>&1
