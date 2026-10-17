#!/bin/sh
# #84 샌드박스를 띄우고 파라미터를 옮긴다. 호스트에서 돌린다(도커를 부른다). 운영으로 보는 주 DB의 파라미터 둘을 바꿔 두고
# sandbox-create가 그 값을 옮기는지, sandbox-diff가 차이를 내는지, sandbox-drop이 지우는지 본다. setup.sh를 먼저 돌린다.
# 호스트에 mysql 클라이언트가 없어 --snapshot(덤프 되붓기)은 여기서 돌리지 않는다. 되붓기는 reset과 같은 길이다(실험 05, 16).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; cd "$HERE"; . ./common.sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
D=$ROOT/build/install/dbreplay/bin/dbreplay
export DBREPLAY_PASSWORD=replay DBREPLAY_SANDBOX_PASSWORD=sandbox
mp -e "SET PERSIST innodb_flush_log_at_trx_commit = 2; SET PERSIST sort_buffer_size = 1048576"
pp -c "ALTER SYSTEM SET work_mem = '8MB'" >/dev/null; pp -c "SELECT pg_reload_conf()" >/dev/null
for name in dbreplay-exp18-sandbox-mysql dbreplay-exp18-sandbox-pg; do docker rm -f -v $name >/dev/null 2>&1 || true; done
echo "== MySQL"
$D sandbox-create --like "jdbc:mysql://127.0.0.1:33061/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true" --user root \
  --name dbreplay-exp18-sandbox-mysql --port 33062 2>&1 | tail -25
$D sandbox-diff --like "jdbc:mysql://127.0.0.1:33061/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true" --user root \
  --url "jdbc:mysql://127.0.0.1:33062/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true" 2>&1 | tail -15 || true
docker exec dbreplay-exp18-sandbox-mysql mysql -uroot -psandbox -N -e \
  "SELECT @@innodb_flush_log_at_trx_commit, @@sort_buffer_size, @@version, (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'sbtest')" 2>/dev/null
echo "== PostgreSQL"
$D sandbox-create --like "jdbc:postgresql://127.0.0.1:54321/shop" --user postgres --name dbreplay-exp18-sandbox-pg --port 54322 2>&1 | tail -15
docker exec dbreplay-exp18-sandbox-pg psql -U postgres -t -A -c "SHOW work_mem" -c "SHOW server_version"
echo "== 지우기"
$D sandbox-drop --name dbreplay-exp18-sandbox-mysql; $D sandbox-drop --name dbreplay-exp18-sandbox-pg
docker ps -a --format '{{.Names}}' | grep -c sandbox || true
mp -e "RESET PERSIST innodb_flush_log_at_trx_commit; RESET PERSIST sort_buffer_size; SET GLOBAL innodb_flush_log_at_trx_commit = 1; SET GLOBAL sort_buffer_size = 262144"
pp -c "ALTER SYSTEM RESET work_mem" >/dev/null; pp -c "SELECT pg_reload_conf()" >/dev/null
