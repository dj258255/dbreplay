# 확인 스크립트들이 함께 쓰는 것. sh로 읽어 들인다(. ./common.sh).
NET=dbreplay-exp16_default
RUN="docker run --rm --network $NET -v $ROOT/build/install/dbreplay:/app:ro -v $HERE:/exp -e DBREPLAY_PASSWORD=replay dbreplay-exp-runner"
MP="jdbc:mysql://mysql-primary:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
MR="jdbc:mysql://mysql-replica:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"
PP="jdbc:postgresql://pg-primary:5432/shop?preferQueryMode=simple"
PR="jdbc:postgresql://pg-replica:5432/shop?preferQueryMode=simple"
mp() { docker compose exec -T mysql-primary mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
mr() { docker compose exec -T mysql-replica mysql -h127.0.0.1 -uroot -preplay "$@" 2>/dev/null </dev/null; }
pp() { docker compose exec -T pg-primary psql -U postgres -d shop -q -t -A "$@" </dev/null; }
pr() { docker compose exec -T pg-replica psql -U postgres -d shop -q -t -A "$@" </dev/null; }
sb() { host=$1; script=$2; shift 2
  docker run --rm --network $NET dbreplay-exp01-sysbench sysbench "$script" --mysql-host="$host" --mysql-user=sb --mysql-password=sb \
    --mysql-db=sbtest --tables=4 --table-size=100000 "$@" </dev/null; }
