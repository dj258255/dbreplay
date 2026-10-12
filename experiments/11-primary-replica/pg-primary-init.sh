#!/bin/sh
# 종 DB가 복제 연결로 들어올 수 있게 한다. 이미지의 기본 pg_hba.conf는 바깥에서 오는 복제 연결을 받지 않는다.
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
