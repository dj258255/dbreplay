#!/bin/sh
# 여섯 세션을 동시에 돌린 뒤 슬로우 로그와 performance_schema 정답지를 out/에 떨군다.
set -eu
cd "$(dirname "$0")/.."
M="docker compose exec -T mysql84 mysql -h127.0.0.1 -uroot -preplay"

$M shop < mysql/setup.sql 2>/dev/null
# 준비 단계의 기록을 지운다. 로그 파일은 비우고 다시 열게 한다.
docker compose exec -T mysql84 sh -c ': > /var/lib/mysql/slow.log'
$M -e "FLUSH SLOW LOGS; TRUNCATE performance_schema.events_statements_history_long; TRUNCATE performance_schema.events_transactions_history_long" 2>/dev/null

for s in mysql/sessions/s*.sql; do
  # --comments: 꼬리표 주석을 서버까지 보낸다. --force: 일부러 넣은 오류 문장 뒤에도 계속한다.
  $M --comments --force shop < "$s" >/dev/null 2>&1 &
done
wait

docker compose exec -T mysql84 cat /var/lib/mysql/slow.log > out/mysql-slow.log
$M -N -B -r -e "
SELECT JSON_OBJECT('thread', THREAD_ID, 'event', EVENT_ID, 'nest', NESTING_EVENT_ID, 'nest_type', NESTING_EVENT_TYPE,
                   'name', EVENT_NAME, 'start', TIMER_START, 'sql', SQL_TEXT)
FROM performance_schema.events_statements_history_long ORDER BY THREAD_ID, EVENT_ID" 2>/dev/null > out/mysql-ps-statements.jsonl
$M -N -B -r -e "
SELECT JSON_OBJECT('thread', THREAD_ID, 'event', EVENT_ID, 'end_event', END_EVENT_ID, 'nest', NESTING_EVENT_ID,
                   'nest_type', NESTING_EVENT_TYPE, 'state', STATE, 'autocommit', AUTOCOMMIT, 'trx_id', TRX_ID)
FROM performance_schema.events_transactions_history_long ORDER BY THREAD_ID, EVENT_ID" 2>/dev/null > out/mysql-ps-transactions.jsonl
wc -l out/mysql-slow.log out/mysql-ps-statements.jsonl out/mysql-ps-transactions.jsonl
