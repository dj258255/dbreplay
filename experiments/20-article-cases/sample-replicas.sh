#!/bin/sh
# 종 DB 둘의 실행 중인 연결을 0.2초마다 /exp/out/sessions.txt에 쌓는다(에포크 밀리초, 호스트, 개수). 재생기 컨테이너 안에서 돈다.
# 세는 방식은 dbreplay의 active_sessions와 같다(쉬는 연결, 상주 스레드, 복제 스레드, 이 조회 자신을 뺀다)
Q="SELECT COUNT(*) FROM performance_schema.threads WHERE TYPE = 'FOREGROUND' AND PROCESSLIST_ID <> CONNECTION_ID()
   AND PROCESSLIST_COMMAND NOT IN ('Sleep', 'Daemon', 'Binlog Dump', 'Binlog Dump GTID') AND NAME NOT LIKE 'thread/sql/replica\\_%'"
while :; do
  for h in mysql-replica mysql-replica2; do
    n=$(MYSQL_PWD=replay mysql -h $h -uroot -N -e "$Q" 2>/dev/null || echo -1)
    echo "$(date +%s%3N) $h $n"
  done
  sleep 0.2
done >> /exp/out/sessions.txt
