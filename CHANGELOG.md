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

### 알려진 한계

- 변환기가 로그를 전부 메모리에 올린다.
- 녹화 파일에 리터럴 값이 그대로 남는다.
- 수치는 노트북의 도커에서 잰 것이고, 상한 배속은 실행마다 흔들린다.
