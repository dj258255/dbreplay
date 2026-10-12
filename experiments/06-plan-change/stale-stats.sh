#!/bin/sh
# 통계를 일부러 틀어 놓는다. 되돌리기의 --ready-command로 불려서 도구가 통계를 다시 잡은 뒤에 돈다.
# 실험 02에서 덤프를 되부은 직후 sbtest1의 행 수가 46으로 남아 범위 조회가 풀 스캔이 됐다. 그 상태를 다시 만든다.
set -eu
M="mysql -hmysql84 -uroot -preplay sbtest"
$M -e "ALTER TABLE sbtest1 STATS_AUTO_RECALC=0" 2>/dev/null
$M -e "UPDATE mysql.innodb_table_stats SET n_rows = 46, clustered_index_size = 1 WHERE database_name = 'sbtest' AND table_name = 'sbtest1';
       UPDATE mysql.innodb_index_stats SET stat_value = 46 WHERE database_name = 'sbtest' AND table_name = 'sbtest1' AND stat_name LIKE 'n_diff_pfx%';
       UPDATE mysql.innodb_index_stats SET stat_value = 1 WHERE database_name = 'sbtest' AND table_name = 'sbtest1' AND stat_name IN ('n_leaf_pages', 'size');
       FLUSH TABLE sbtest1" 2>/dev/null
$M -e "EXPLAIN SELECT c FROM sbtest1 WHERE id BETWEEN 50000 AND 50099\G" 2>/dev/null | grep -E "type:|rows:"
