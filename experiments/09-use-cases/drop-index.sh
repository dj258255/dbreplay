#!/bin/sh
# 인덱스 장면의 변경. 되돌리기의 --change-command로 불린다. 덤프를 되부은 뒤, 통계를 다시 잡기 전에 돈다.
set -eu
mysql -hmysql84 -uroot -preplay sbtest -e "ALTER TABLE sbtest1 DROP INDEX k_1" 2>/dev/null
