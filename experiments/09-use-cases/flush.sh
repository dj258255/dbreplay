#!/bin/sh
# 파라미터 장면의 변경. $1이 innodb_flush_log_at_trx_commit의 값이다.
# 서버는 되돌려도 그대로 떠 있어서 변경 전에도 값을 명시해 준다.
set -eu
mysql -hmysql84 -uroot -preplay -e "SET GLOBAL innodb_flush_log_at_trx_commit = $1" 2>/dev/null
