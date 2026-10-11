#!/bin/sh
# 이 저장소의 라벨을 만든다. 이미 있으면 설명과 색만 맞춘다.
# 사용: scripts/labels.sh [소유자/저장소]
set -eu
REPO=${1:-}
label() {
  gh label create "$1" --description "$2" --color "$3" --force ${REPO:+--repo "$REPO"} >/dev/null
  echo "$1"
}

# 종류: 이슈와 PR마다 하나
label "type:feature"    "새 기능"                               "0e8a16"
label "type:bug"        "결함. 동작이 문서나 기대와 다르다"     "d73a4a"
label "type:experiment" "수치로 결론을 내는 실험과 측정"        "c5def5"
label "type:test"       "테스트와 검증 기반"                    "bfd4f2"
label "type:docs"       "문서와 변경 이력"                      "0075ca"
label "type:decision"   "선택지 사이에서 사람이 정해야 하는 것" "fbca04"
label "type:release"    "릴리스 준비와 게시"                    "e4e669"

# 영역: 이슈와 PR마다 하나 이상
label "area:convert"    "서버 로그를 트래픽 파일로 바꾸기"      "1d76db"
label "area:replay"     "재생기"                                "0b6e99"
label "area:report"     "전후 비교와 리포트"                    "5319e7"
label "area:explore"    "용량 탐색, 알람, 수위, 평가"           "006b75"
label "area:sandbox"    "샌드박스 구축과 되돌리기"              "e99695"
label "area:mysql"      "MySQL에만 해당"                        "f9d0c4"
label "area:postgres"   "PostgreSQL에만 해당"                   "d4c5f9"
label "area:workflow"   "저장소 운영과 작업 기록"               "cfd3d7"

# 묶음: 상위 이슈와 함께 쓴다
label "track:article"   "메이투안 글에 있는데 아직 없는 것"     "b60205"
label "track:scale"     "실제 크기의 로그와 장비에서 버티기"    "7057ff"
