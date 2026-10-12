#!/bin/sh
# 검증하려는 변경. 되돌리기의 --change-command로 불린다. 메이투안 글의 사례와 같은 테이블 압축이다.
set -eu
for t in sbtest1 sbtest2 sbtest3 sbtest4; do
  mysql -hmysql84 -uroot -preplay sbtest -e "ALTER TABLE $t ROW_FORMAT=COMPRESSED KEY_BLOCK_SIZE=8" 2>/dev/null
done
