처음 돌린 실행. MySQL의 all 수준 첫 재생이 끝나지 않아 13:44에 멈췄다.
13:34에 시작한 1배속 재생이 9분 뒤에도 돌고 있었다. 그때 서버에는 실행 중인 연결이 502개였고(재생기의 연결 한도가 500이다),
그중 494개가 `SELECT DISTINCT c FROM sbtestN WHERE id BETWEEN ? AND ? ORDER BY c`였다. 가장 오래된 것이 558초째였다.
락 대기는 0이었다. MySQL 컨테이너의 CPU는 659%였다.
원본의 범위는 100행이다(BETWEEN 50100 AND 50199). all 수준은 두 끝을 따로 바꿔서 BETWEEN 22701 AND 63063 같은 4만 행짜리가 됐다.
