#!/bin/sh
# #63: snapshot과 convert가 만든 파일을 소유자만 읽고 쓸 수 있는가. 이미 있던 파일을 덮어쓸 때도. setup.sh를 먼저 돌린다.
# 권한은 컨테이너 안의 임시 폴더에서 본다. 맥의 도커가 마운트한 폴더는 권한을 그대로 옮기지 않는다.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
. ./common.sh

$RUN sh -c '
  set -e
  cd /tmp
  : > mysql.sql; : > pg.sql; chmod 644 mysql.sql pg.sql            # 다른 사용자가 읽을 수 있는 파일이 이미 있다
  /app/bin/dbreplay snapshot --url "'"$MP"'" --user root --out /tmp/mysql.sql
  /app/bin/dbreplay snapshot --url "'"$PP"'" --user postgres --out /tmp/pg.sql
  printf "# Time: 2026-10-11T00:00:01.000000Z\n# User@Host: root[root] @ localhost []  Id:     3\n# Query_time: 0.000100  Lock_time: 0.000000 Rows_sent: 1  Rows_examined: 1\nSET timestamp=1791676801;\nSELECT 1;\n" > slow.log
  : > trace.jsonl; chmod 644 trace.jsonl
  /app/bin/dbreplay convert --in /tmp/slow.log --out /tmp/trace.jsonl
  stat -c "%a %s %n" mysql.sql pg.sql trace.jsonl
' </dev/null
