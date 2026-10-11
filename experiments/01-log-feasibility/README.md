# 실험 01: 서버 로그만으로 재생에 필요한 정보가 나오는가

2026-10-11, MySQL 8.4.11과 PostgreSQL 16 (도커, Apple Silicon 노트북).

`docs/ORIGIN.md` 12절의 미확인 항목 두 가지를 확인했다.

- MySQL 슬로우 로그만으로 트랜잭션 경계를 복원할 수 있는가
- 임계 0의 슬로우 로그가 대상에 붙이는 부하는 얼마인가

## 결론

- **트랜잭션 경계는 복원된다.** 여섯 세션 시나리오의 문장 37개에서 로그로 복원한 묶음이
  `performance_schema`의 정답지와 전부 일치했다.
- **부하는 이 환경에서 편차와 구분되지 않는 크기였다.** 처리량이 평균 7% 낮게 나왔는데, 같은 조건을
  세 번 돌린 편차가 7~9%다. 수십 %는 아니라는 것까지만 말할 수 있다.
- **실제 제약은 로그 양이다.** 문장 하나에 242바이트, 부가 필드를 켜면 596바이트가 쌓인다.

## 다시 돌리는 법

```sh
docker compose up -d --wait
./mysql/run.sh
python3 mysql/check.py out/mysql-slow.log out/mysql-ps-statements.jsonl out/mysql-ps-transactions.jsonl
./mysql/use-db.sh
./bench/run.sh        # 약 6분
./pg/run.sh
docker compose down -v
```

## MySQL 슬로우 로그에서 확인한 것

설정은 `slow_query_log=ON`, `long_query_time=0`, `log_slow_extra=ON`, `log_slow_admin_statements=ON`이다.

| 항목 | 관찰 |
|---|---|
| 세션 식별 | 문장마다 `Id:`에 연결 번호가 찍힌다 |
| 트랜잭션 경계 | `BEGIN`, `START TRANSACTION`, `COMMIT`, `ROLLBACK`이 문장으로 남는다 |
| 시각 | `# Time:`은 문장이 끝난 시각이고 마이크로초 단위다. 시작 시각은 `Time - Query_time`과 같고, `log_slow_extra`를 켜면 `Start:`로도 찍힌다 |
| 파일 순서 | 끝난 순서다. 락을 0.4초 기다린 `UPDATE`가 그 락을 푼 `COMMIT`보다 뒤에 적힌다. 재생 순서는 시작 시각으로 다시 세워야 한다 |
| 락 대기 | `Lock_time`에 남는다. 위 `UPDATE`는 `Query_time` 0.4077초 중 0.4072초가 락 대기였다 |
| 바이너리 프로토콜 prepared statement | 실행은 값이 채워진 SQL 본문으로 남는다. 준비는 `# administrator command: Prepare;`로만 남는다 |
| SQL 문법의 `PREPARE`/`EXECUTE` | `PREPARE`는 본문이 가려져 `PREPARE st FROM ...`로 남고, `EXECUTE`는 값이 채워진 본문으로 남는다 |
| 저장 프로시저 | `CALL` 한 줄만 남는다. 안쪽 문장은 따로 남지 않는다 |
| 실패한 문장 | `log_slow_extra`를 켜면 `Errno:`에 오류 번호가 남는다. 끄면 실패 여부를 알 수 없다 |
| 연결 종료 | `# administrator command: Quit;`으로 남는다. 커밋 없이 끊긴 트랜잭션을 여기서 닫을 수 있다 |
| 연결 시작 | 남지 않는다. 그 연결의 첫 문장으로 추정해야 한다 |

### 복원 규칙

`mysql/check.py`의 `rebuild_from_log`가 연결별로 문장을 시작 시각 순으로 훑는다.

- `BEGIN`과 `START TRANSACTION`은 묶음을 연다. `COMMIT`과 `ROLLBACK`은 닫는다.
- DDL처럼 암묵적으로 커밋하는 문장은 열린 묶음에 들어간 뒤 그 묶음을 닫는다.
- `SET autocommit=0` 뒤에는 테이블을 건드리는 첫 문장이 묶음을 연다. 조회도 연다.
- `Quit`은 열린 묶음을 닫는다.

정답지는 `events_statements_history_long`의 `NESTING_EVENT_ID`와 `events_transactions_history_long`을
이어 만든다. 문장에 `/* s1.2 */` 같은 꼬리표를 달아 양쪽을 맞췄다.

### 로그로는 알 수 없는 것

- 녹화를 시작하기 전에 이미 열려 있던 연결의 상태. `autocommit=0`, 세션 변수, 임시 테이블, 진행 중이던
  트랜잭션이 여기에 든다.
- 기본 데이터베이스는 `use db;` 줄로 남는데, 기준이 연결이 아니라 로그 파일이다. 직전에 적힌 문장과
  데이터베이스가 다를 때만 찍힌다(`mysql/use-db.sh`, `out/mysql-use-db.log`). 그래서 기본 데이터베이스가
  없는 연결의 문장은 직전 문장의 데이터베이스와 구분되지 않는다.
- 한 패킷에 여러 문장을 실어 보내는 경우는 시험하지 않았다.
- 리터럴 값이 그대로 남는다. 개인정보가 섞인 SQL은 그대로 파일이 된다.

## 부하 측정

sysbench 1.0.20 `oltp_read_write`, 테이블 4개에 10만 행씩, 스레드 8개, 30초씩 세 바퀴.
세 조건을 번갈아 돌렸고 첫 20초 예열은 버렸다. 원자료는 `out/bench.txt`에 있다.

| 조건 | 처리량 평균(TPS) | 최소~최대 | 끈 상태 대비 | 문장당 로그 | 초당 로그 |
|---|---|---|---|---|---|
| 슬로우 로그 끔 | 1395.5 | 1331.7~1449.4 | 기준 | 0 | 0 |
| 켬, 임계 0 | 1298.6 | 1221.4~1337.7 | -6.9% | 242바이트 | 6.3MB |
| 켬, 임계 0, `log_slow_extra` | 1288.3 | 1233.7~1327.3 | -7.7% | 596바이트 | 15.4MB |

읽을 때 조심할 것이 있다.

- 같은 조건 안의 편차(7~9%)가 조건 사이의 차이(7~8%)와 같은 크기다. 세 바퀴로는 차이를 확정할 수 없다.
- 노트북 도커에서의 상대값이다. 부하 생성기와 서버가 같은 장비의 CPU를 나눠 썼다.
- 로그 파일은 컨테이너 안에 뒀다. 운영 장비의 디스크와 fsync 사정은 다르다.
- 초당 약 26,000문장에서 부가 필드를 켜면 한 시간에 약 55GB가 쌓인다. 녹화 시간을 정할 때의 제약이다.

## PostgreSQL 문장 로그에서 확인한 것

설정은 `log_min_duration_statement=0`, `log_line_prefix='%m [%p] sid=%c vxid=%v xid=%x '`이다.
원자료는 `out/pg.log`에 있다.

| 항목 | 관찰 |
|---|---|
| 세션 식별 | `%c`로 세션 ID가 줄마다 찍힌다 |
| 트랜잭션 경계 | `BEGIN`, `COMMIT`, `ROLLBACK`이 문장으로 남는다. `%v`의 가상 트랜잭션 ID로도 묶인다 |
| 시각 | 줄의 시각은 문장이 끝난 시각이고 밀리초 단위다. 시작 시각은 `duration`을 빼서 구한다 |
| 실패한 문장 | `ERROR`와 `STATEMENT` 두 줄로 남고 `duration`이 없다 |
| 실패한 트랜잭션 | 오류 뒤의 문장은 "current transaction is aborted"로 거절된 것까지 남는다 |
| `PREPARE`/`EXECUTE` | `EXECUTE` 줄에 준비문 본문이 `DETAIL`로 붙는다 |

확장 프로토콜(드라이버의 prepared statement)과 `COPY`는 시험하지 않았다.

## 설계에 주는 것

- 트래픽 파일의 한 레코드는 세션 ID, 시작 시각, 걸린 시간, SQL 본문, 실패 여부를 가져야 한다.
  두 기종의 로그가 모두 이 다섯 가지를 낸다. 락 대기 시간은 MySQL만 낸다.
- 재생기가 세션마다 연결을 하나씩 쓰고 문장을 원래 순서로 보내면 트랜잭션 경계는 서버가 다시 만든다.
  복원한 경계는 녹화 구간 앞뒤에서 잘린 트랜잭션을 버리는 데, 실패한 문장 뒤의 처리를 정하는 데,
  트랜잭션 단위 지연을 재는 데 쓴다.
- 준비 단계(`Prepare`, `PREPARE`, `DEALLOCATE`)는 변환기가 버려도 된다. 실행 줄에 본문이 다 들어 있다.
