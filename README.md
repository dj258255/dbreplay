# dbreplay

운영 DB에 들어온 SQL을 녹화해 샌드박스에 다시 틀고, 두 가지를 잰다.

- 이 변경을 넣으면 어떤 SQL이 얼마나 느려지는가
- 이 DB는 지금 부하의 몇 배에서 알람이 울리는가

메이투안의 [DB 용량 평가 시스템 글](https://tech.meituan.com/2025/10/17/Database-Capacity-Evaluation-System.html)이
한 일을 자체 커널이나 전용 인프라 없이, 서버 로그와 명령줄 도구 하나로 따라간다.
MySQL 8.4와 PostgreSQL 16에서 확인했다.

**아직 실험 단계다.** 수치는 전부 노트북의 도커에서 잰 것이고 실제 운영에서 쓰인 적이 없다.
한계는 [로드맵](docs/work/ROADMAP.md)에 있다.

## 써 보기

JDK 25가 필요하다.

```sh
./gradlew installDist
alias dbreplay=build/install/dbreplay/bin/dbreplay

dbreplay convert --in slow.log --out prod.trace.jsonl
dbreplay replay  --trace prod.trace.jsonl --url jdbc:mysql://sandbox:3306/ --user root --speed 1
dbreplay compare --before before.trace.jsonl --after after.trace.jsonl
dbreplay explore --trace prod.trace.jsonl --url jdbc:mysql://sandbox:3306/ --user root \
  --snapshot snapshot.sql --alarm-command ./alarm.sh --metric-command ./metrics.sh
```

녹화부터 용량 탐색까지 한 번에 돌리는 예는 `experiments/03-mysql-compare-explore/run.sh`와
`experiments/04-postgres/run.sh`에 있다. Docker가 필요하다.

## 실험에서 나온 것

| 실험 | 내용 |
|---|---|
| [01](experiments/01-log-feasibility/README.md) | 서버 로그만으로 세션과 트랜잭션 경계가 복원되는가 |
| [02](experiments/02-replay-fidelity/README.md) | 재생이 원본과 얼마나 같은가 |
| [03](experiments/03-mysql-compare-explore/README.md) | MySQL에서 테이블 압축 전후 비교와 용량 탐색 |
| [04](experiments/04-postgres/README.md) | PostgreSQL에서 같은 흐름 |
| [05](experiments/05-sandbox-reset/README.md) | 되돌린 뒤 기다리면 재생이 원본에 가까워지는가 |
| [06](experiments/06-plan-change/README.md) | 실행 계획이 바뀐 것이 읽은 행 수로 드러나는가 |

## 문서

- [경위와 결정](docs/ORIGIN.md): 왜 만들었고, 무엇을 누가 정했고, 글의 기준이 무엇인가
- [로드맵](docs/work/ROADMAP.md): 끝난 것, 남은 것, 하지 않는 범위
- [검증 기록](docs/verify/VERIFICATION.md): 어느 기능을 무엇으로 확인했는가
- [작업 방식](docs/work/README.md)과 [기여 가이드](CONTRIBUTING.md)
