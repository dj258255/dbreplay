"""compare의 출력 세 개(out/<장면>-compare-1..3.txt)에서 모양별 평균의 변화를 모아 본다.

사용: python3 analyze.py a|b|c
세 번 모두 같은 방향이고 세 번 모두 20%를 넘게 변한 모양만 '변경의 효과'로 꼽는다(이슈 #18의 중단 규칙).
"""
import re
import sys

scene = sys.argv[1]
ROW = re.compile(r"^\s+(\d+)\s+([\d.]+)ms\s+([\d.]+)ms\s+([+-][\d.]+)%\s+([\d.]+)ms\s+([\d.]+)ms\s+(.*)$")
runs = []
for i in (1, 2, 3):
    rows = {}
    section = None
    for line in open(f"out/{scene}-compare-{i}.txt", encoding="utf-8"):
        if not line.startswith(" "):
            section = line.strip()
            continue
        m = ROW.match(line)
        if m and section == "실행 시간을 많이 쓴 순서":
            rows[m.group(7)] = (float(m.group(2)), float(m.group(3)), float(m.group(4)))
    runs.append(rows)

shapes = [s for s in runs[0] if all(s in r for r in runs)]
print(f"세 번 모두에 있는 모양 {len(shapes)}개")
for i, r in enumerate(runs, 1):
    changes = [r[s][2] for s in shapes]
    within = sum(1 for c in changes if abs(c) <= 20)
    print(f"  실행 {i}: 변화가 20% 안인 모양 {within}개, 밖인 모양 {len(changes) - within}개 (가장 큰 것 {max(changes):+.1f}%, 가장 작은 것 {min(changes):+.1f}%)")

print("세 번 모두 같은 방향으로 20% 넘게 변한 모양:")
found = False
for s in shapes:
    c = [r[s][2] for r in runs]
    if all(x > 20 for x in c) or all(x < -20 for x in c):
        found = True
        before = " / ".join(f"{r[s][0]:.3f}" for r in runs)
        after = " / ".join(f"{r[s][1]:.3f}" for r in runs)
        print(f"  {s}\n    평균 앞 {before}ms, 뒤 {after}ms, 변화 " + " / ".join(f"{x:+.1f}%" for x in c))
if not found:
    print("  없음")

mixed = sum(1 for s in shapes if not (all(r[s][2] > 0 for r in runs) or all(r[s][2] < 0 for r in runs)))
print(f"세 번에서 방향이 갈린 모양 {mixed}개")
