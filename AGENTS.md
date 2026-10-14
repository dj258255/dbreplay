# dbreplay

운영 DB에 들어온 SQL을 녹화해 격리된 복제 환경에 다시 트는 도구다. 메이투안의 DB 용량 평가 시스템 글이
한 일을 명령줄 도구 하나의 규모로 따라간다. MySQL 8.4와 PostgreSQL 16을 지원한다.

작업을 시작하기 전에 `docs/ORIGIN.md`를 읽는다. 이 저장소가 생긴 경위, 참고한 글, 기존 도구 조사,
정해진 것과 정해지지 않은 것이 거기 있다. 그 문서의 "제안"은 합의된 결정이 아니다.
지금 어디까지 왔고 무엇이 남았는지는 `docs/work/ROADMAP.md`에 있다.
글의 항목 하나하나가 이 저장소에 있는지는 `docs/verify/ARTICLE.md`에 있다. "글대로 다 됐다"고 말하기 전에 그 표를 본다.

## 구조

Java 25와 Gradle로 만든다.

| 패키지 | 하는 일 |
|---|---|
| `io.dbreplay.trace` | 기종과 무관한 트래픽 파일 형식 (JSON Lines) |
| `io.dbreplay.convert` | 두 기종의 변환기가 함께 쓰는 부분 |
| `io.dbreplay.mysql` | MySQL 슬로우 로그 변환기 |
| `io.dbreplay.postgres` | PostgreSQL csvlog 변환기 |
| `io.dbreplay.replay` | 재생기. 트랜잭션·문장·연결 단위로 내보낸다 |
| `io.dbreplay.report` | SQL 모양별 전후 비교, Markdown 리포트 |
| `io.dbreplay.metrics` | 재생하는 동안 대상의 부하 지표를 뜬다. DB 접속으로 읽는 것과 사용자 명령으로 받는 것 |
| `io.dbreplay.sandbox` | 샌드박스를 녹화 시작 시점으로 되돌린다. 덤프 되붓기, 변경, 통계, 대기 |
| `io.dbreplay.explore` | 용량 탐색, 알람 판정, 수위, 평가의 유효성 |

```sh
./gradlew test
./gradlew installDist     # build/install/dbreplay/bin/dbreplay
dbreplay convert --in <서버 로그> --out <트래픽 파일> [--from mysql-slow|postgres-csv] [--mask strings]
dbreplay replay  --trace <트래픽 파일> --url <JDBC URL> --user <계정> --speed 1 [--unit transaction|statement|session]
dbreplay compare --before <트래픽 파일> --after <트래픽 파일>
dbreplay report  --before <트래픽 파일> --after <트래픽 파일> --out <리포트.md>
dbreplay explore --trace <트래픽 파일> --url <JDBC URL> --user <계정> --snapshot <덤프 파일> --alarm-command <명령> [--repeat 5]
dbreplay snapshot --url <JDBC URL> --user <계정> --out <덤프 파일>
dbreplay reset   --url <JDBC URL> --user <계정> --snapshot <덤프 파일>
dbreplay check   --evaluation <평가 파일> --url <JDBC URL> --user <계정>
dbreplay manage  --targets <대상 목록.json>
dbreplay sample  --url <JDBC URL> --user <계정> --seconds <초>
```

## 판정과 재생 방식

메이투안 글이 말한 그대로 따른다. 바꾸기 전에 `docs/ORIGIN.md` 13절의 "글의 기준"을 본다.

- 과부하는 알람으로만 판정한다. 재생기가 잰 값은 그 라운드가 왜곡됐는지를 가리는 데만 쓴다.
- 상한은 한 번의 탐색으로 말하지 않는다. 같은 절차의 상한이 실행마다 다르다(실험 12). `explore --repeat N`으로 되풀이해
  범위를 내고, 수위 판정과 평가 파일에는 가장 낮은 값을 쓴다(범수 님 결정, 2026-10-13).
- 기본 재생 단위는 트랜잭션이다. 대상이 느려져도 다음 트랜잭션은 제 시각에 출발한다.
- 종 DB의 트래픽은 종 DB에서 따로 녹화해 `--replica-trace`로 함께 튼다. 문장 하나씩 독립으로 나간다.
  복제 지연은 `--replica-url`을 주면 뜨고, 알람으로 건다. 주 DB가 버티는 것과 종 DB가 따라오는 것은 다른 한계다(실험 11).
- 글에 값이 없는 기본값(정밀도, 왜곡 비율, 유효 기간 등)은 Claude가 고르고 범수 님이 확정했다(2026-10-15, #14). ORIGIN.md 2절에 근거가 있는 것과 없는 것을 나눠 적었다.
  새 기본값을 넣을 때도 누가 무엇을 근거로 골랐는지 같은 자리에 적는다.

## 코드 규칙

- 기종마다 다른 지식(암묵적 커밋, 로그 형식, 오류 번호)은 그 기종의 변환기 안에 둔다. 재생기와 비교, 탐색은
  기종을 몰라야 한다. 재생기가 기종을 가르는 곳은 JDBC URL 하나다.
- 주석과 문서는 한국어로 쓴다. 주석에는 코드가 왜 그렇게 됐는지를 적고, 실험에서 나온 근거가 있으면 실험 번호를 적는다.
- 들여쓰기는 탭이다. 이모지는 쓰지 않는다.
- 의존성을 더할 때는 `build.gradle`에 왜 필요한지 한 줄을 적는다.

## 검증

- 동작을 바꾸면 테스트를 함께 고친다. 실제 DB에서만 드러나는 것은 `experiments/`의 스크립트로 확인한다.
- 실험은 `experiments/NN-이름/`에 둔다. 다시 돌리는 명령, 조건, 결과, 한계를 그 폴더의 `README.md`에 적는다.
  어느 기능을 어느 실험과 테스트가 확인했는지는 `docs/verify/VERIFICATION.md`에 모은다.
- 수치를 적을 때는 무엇을 잰 것인지(지연, 처리량, 오류 수), 어떤 조건이었는지, 몇 번 돌렸는지를 함께 적는다.
  "빨라졌다", "안정적이다"로 끝내지 않는다.
- 한 번 돌린 결과로 판정하지 않는다. 실험 03에서 같은 절차의 상한 배속이 7.50과 8.50으로 갈렸다.
  반복하지 못했으면 한 번 돌렸다고 적는다.
- 예상과 다르게 나온 결과와 실패도 지우지 않고 원인과 함께 남긴다.
- 실험의 수치는 재생기를 DB와 같은 도커 네트워크에서 돌린 값이다. 호스트에서 돌리면 왕복 시간이 달라진다.
- 전후 비교는 재생할 때마다 대상의 로그를 켜고 그 로그끼리 한다. 원본 로그와 재생 로그는 같은 자가 아니다.

## 작업 기록 (이슈·PR·커밋)

왜 이렇게 하는지와 한 건의 수명은 `docs/work/README.md`에 있다.

- 문제 하나 = 이슈 하나, 영역 하나 = PR 하나, 변경 한 가지 = 커밋 하나. 여러 건을 묶어 볼 때는 하위 이슈
  체크리스트만 있는 상위 이슈를 둔다.
- 착수 전에 이슈에 예상(시간·일 단위), 예상 마감(날짜만), 완료 조건을 적는다. 끝나면 실제 마감과 어긋난 이유를
  채운다. 마감을 넘기면 원래 값을 지우지 않고 새 예상을 한 줄 더 적는다. **없는 예상을 뒤늦게 만들어 넣지 않는다.**
- 완료 조건은 돌릴 명령과 볼 출력으로 적는다. 결론이 어느 쪽으로 나든 완료가 되게 적는다.
- 측정으로 정하는 일은 어떤 수치가 나오면 그만둘지를 재기 전에 적는다.
- 선택지가 둘 이상인 결정은 이슈에 선택지, 판단 기준, 고른 것, 포기한 것을 적는다. 결정한 사람도 적는다.
- 이슈와 PR마다 `type:*` 하나와 `area:*` 하나 이상, 그리고 릴리스 버전 마일스톤을 단다. 작업 묶음은 마일스톤이
  아니라 `track:*` 라벨과 상위 이슈로 둔다.
- 커밋 메시지는 한국어로, 제목에 변경의 의도가 드러나게 쓴다. 본문에는 왜 바꿨는지와 무엇으로 확인했는지를 적는다.
- 다음 릴리스에 묶일 변경은 `CHANGELOG.md`의 `[Unreleased]`에 쌓는다. 릴리스는 한 이야기로 묶이는 범위에서 한 번 낸다.
- main은 보호돼 있다(2026-10-14). CI의 `test`가 통과하지 않은 PR은 머지되지 않고, main에 직접 푸시할 수 없다. 관리자에게도 적용된다.
- 머지는 CI가 통과한 것을 종료 코드로 확인한 뒤에 한다. 확인 명령의 출력을 파이프로 넘기면 종료 코드가 사라진다.
  `gh pr checks <번호> --watch`가 0으로 끝났을 때만 `gh pr merge`를 부른다. 로컬의 `./gradlew test`도 같다. (#34)
- 끝난 일은 `docs/work/ROADMAP.md`에 옮긴다. 로드맵만 읽어도 지금 어디까지 왔는지 알 수 있어야 한다.
