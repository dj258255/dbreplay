#!/bin/sh
# --profile-command의 예. 재생기 컨테이너 안에서 돈다. 한 줄에 '이름 값'을 낸다.
# 실제로는 DB 서버의 사양을 내야 한다(클라우드의 인스턴스 정보 조회, 장비 대장 등). 여기서는 이 컨테이너가 보는 값을 낸다.
echo "cpus ${FAKE_CPUS:-$(nproc)}"
awk '/MemTotal/ {printf "memory_gb %.0f\n", $2 / 1024 / 1024}' /proc/meminfo
echo "model $(uname -m)"
