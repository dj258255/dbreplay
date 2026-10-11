#!/bin/sh
# PostgreSQL 문장 로그에 세션, 트랜잭션, 시각이 어떻게 남는지 본다.
set -eu
cd "$(dirname "$0")/.."
P="docker compose exec -T pg16 psql -U postgres -d shop -q -v ON_ERROR_STOP=0"

$P -c "DROP TABLE IF EXISTS accounts; CREATE TABLE accounts (id INT PRIMARY KEY, balance INT NOT NULL);
       INSERT INTO accounts VALUES (1,100),(2,100),(3,100)" >/dev/null
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)

$P >/dev/null 2>&1 <<'SQL' &
BEGIN /* s1.1 */;
UPDATE /* s1.2 */ accounts SET balance = balance - 10 WHERE id = 1;
SELECT /* s1.3 */ pg_sleep(0.6);
UPDATE /* s1.4 */ accounts SET balance = balance + 10 WHERE id = 2;
COMMIT /* s1.5 */;
SELECT /* s1.6 */ balance FROM accounts WHERE id = 1;
SQL
$P >/dev/null 2>&1 <<'SQL' &
SELECT /* s2.1 */ pg_sleep(0.2);
BEGIN /* s2.2 */;
UPDATE /* s2.3 */ accounts SET balance = balance - 5 WHERE id = 1;
SELECT /* s2.4 */ * FROM no_such_table;
UPDATE /* s2.5 */ accounts SET balance = balance - 5 WHERE id = 3;
ROLLBACK /* s2.6 */;
PREPARE st(int, int) AS UPDATE /* s2.7p */ accounts SET balance = balance + $1 WHERE id = $2;
EXECUTE /* s2.8 */ st(2, 3);
SQL
wait

docker compose logs --no-log-prefix --since "$since" pg16 2>&1 | grep -E "sid=" > out/pg.log
cat out/pg.log
