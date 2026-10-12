# Changelog

이 프로젝트의 주요 변경을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를
따르고, 버전은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따른다.

## [Unreleased]

다음 릴리스에 묶일 변경을 여기 쌓는다. 아직 낸 릴리스가 없다.

### 더한 것

- `dbreplay convert`: MySQL 슬로우 로그와 PostgreSQL csvlog를 기종과 무관한 트래픽 파일로 바꾼다.
- `dbreplay replay`: 트래픽 파일을 배속을 정해 다시 튼다. 단위는 트랜잭션(기본), 문장, 연결 중에서 고른다.
- `dbreplay compare`: 두 트래픽 파일을 SQL 모양별로 비교해 나빠진 SQL과 좋아진 SQL을 낸다.
- `dbreplay explore`: 알람이 울리는 배속을 찾아 상한과 수위를 낸다. 평가를 파일로 남길 수 있다.
- `dbreplay check`: 남긴 평가가 아직 유효한지 본다.
- 부하 지표 수집(#2). 재생하는 동안 대상에서 실행 중인 연결 수와 기준 시간을 넘겨 실행 중인 문장 수를 뜬다.
  CPU와 Load Average처럼 DB 접속으로 못 읽는 지표는 `--metric-command`로 받는다. `explore`가 라운드별 지표를
  표로 내고, `--alarm-metric 이름>=값`으로 어느 지표에든 알람을 걸 수 있다.
- `dbreplay sample`: 녹화하는 동안 운영 쪽의 부하 지표를 떠 둔다.
- 샌드박스 되돌리기(#4). `--snapshot <덤프 파일>`을 주면 도구가 덤프를 되붓고 통계를 다시 잡는다. MySQL에서는 서버가
  조용해질 때까지 기다린다. 검증하려는 변경은 `--change-command`로 넣는다. `dbreplay reset`으로 따로 부를 수도 있다.
  mysql이나 psql 클라이언트가 있어야 한다. `--reset-command`는 그대로 쓸 수 있다.

- `compare`가 읽은 행 수와 오류의 변화를 낸다(#19). 문장당 읽는 행 수가 2배 이상 늘어난 SQL 모양, 새로 난 오류와
  사라진 오류의 번호를 보여 준다. 실행 계획이 바뀐 것과 호환성 문제가 실행 시간과 따로 보인다.
  트래픽 파일에 `rowsSent`와 `rowsExamined`가 더해졌다. MySQL에서만 채워진다. 예전 파일도 그대로 읽는다.

- `dbreplay report`(#5). 변경 전후의 SQL 비교, 읽은 행 수, 오류, 부하 지표, 달라진 파라미터, 상한과 수위를 Markdown 문서
  하나로 낸다. 재생할 때 `--run-out`으로 그때의 대상 설정과 지표를 남기면 리포트에 실린다.

### 바뀐 것

- `--alarm-active-sessions <개수>`는 `--alarm-metric active_sessions>=<개수>`의 줄임으로 남는다.

### 고친 것

- 오류 메시지와 평가 파일에 암호가 실릴 수 있던 곳(#21, #24, #27). 읽지 못한 URL은 메시지에 싣지 않는다.
  평가 파일은 알람 확인 명령의 원문 대신 지문을 적고, 원문이 든 예전 평가 파일도 지문으로 바꿔 비교한다.

### 알려진 한계

- 변환기가 로그를 전부 메모리에 올린다.
- 녹화 파일에 리터럴 값이 그대로 남는다.
- 수치는 노트북의 도커에서 잰 것이고, 상한 배속은 실행마다 흔들린다.
