# 기여 가이드

이 문서는 기여 절차를 설명합니다. 코드와 기록의 규칙은 [AGENTS.md](AGENTS.md)를 봅니다.

## 시작하기

JDK 25가 필요합니다. 실험을 돌리려면 Docker가 필요합니다.

```bash
./gradlew test
./gradlew installDist
build/install/dbreplay/bin/dbreplay        # 사용법이 나옵니다
```

실제 DB에서 녹화부터 용량 탐색까지 돌려 보려면 `experiments/03-mysql-compare-explore/run.sh`나
`experiments/04-postgres/run.sh`를 실행합니다. 각각 14분, 7분쯤 걸립니다.

## 변경을 올리기 전에

- `./gradlew test`가 통과해야 합니다. main은 보호돼 있어서 CI의 `test`가 통과하지 않은 PR은 머지되지 않습니다.
- 동작이 바뀌면 테스트를 함께 고칩니다. 실제 DB에서만 드러나는 것은 `experiments/`에 스크립트와 결과를 남깁니다.
- 수치를 주장하려면 무엇을, 어떤 조건에서, 몇 번 쟀는지를 함께 적습니다. 측정 없는 개선 주장은 받지 않습니다.
- 확인한 내용은 `docs/verify/VERIFICATION.md`에 한 줄을 더합니다.
- 커밋 메시지는 한국어로, 제목에 변경의 의도가 드러나게 씁니다. 이모지는 쓰지 않습니다.

## 이슈와 PR

- 착수할 작업은 "작업" 템플릿으로 엽니다. 예상(시간·일 단위), 예상 마감(날짜만), 완료 조건을 먼저 적고,
  끝나면 실제 마감과 어긋난 이유를 채웁니다.
- 수치로 결론을 내는 일은 "실험" 템플릿으로 엽니다. 가설과 중단 규칙을 재기 전에 적습니다.
- 문제 하나에 이슈 하나, 영역 하나에 PR 하나, 변경 한 가지에 커밋 하나로 나눕니다.
- 이슈와 PR에는 `type:*`과 `area:*` 라벨, 마일스톤을 답니다. 라벨의 뜻은 `scripts/labels.sh`에 있습니다.
- PR 본문에는 닫는 이슈와 검증한 방법을 적습니다.

한 건이 어떤 순서로 흘러가는지와 그렇게 하는 이유는 [docs/work/README.md](docs/work/README.md)에 있습니다.
