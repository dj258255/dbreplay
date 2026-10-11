# 실험에서 재생기를 DB와 같은 도커 네트워크 안에서 돌리기 위한 이미지.
# 데이터 되돌리기 명령이 쓰는 mysql과 psql 클라이언트를 함께 넣는다.
FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-mysql-client postgresql-client \
 && rm -rf /var/lib/apt/lists/*
