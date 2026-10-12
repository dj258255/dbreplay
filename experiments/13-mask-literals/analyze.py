"""compare의 출력 여러 개에서 모양별 평균의 변화를 모아 본다.

사용: python3 analyze.py <compare 출력 파일> <compare 출력 파일> ...
파일마다 변화가 10%, 20% 안인 모양의 수를 내고, 모든 파일에서 같은 방향으로 20% 넘게 변한 모양을 꼽는다.
"""
import re
import sys

ROW = re.compile(r"^\s+(\d+)\s+([\d.]+)ms\s+([\d.]+)ms\s+([+-][\d.]+)%\s+([\d.]+)ms\s+([\d.]+)ms\s+(.*)$")
runs = []
for path in sys.argv[1:]:
    rows = {}
    section = None
    for line in open(path, encoding="utf-8"):
        if not line.startswith(" "):
            section = line.strip()
            continue
        m = ROW.match(line)
        if m and section == "실행 시간을 많이 쓴 순서":
            rows[m.group(7)] = (float(m.group(2)), float(m.group(3)), float(m.group(4)))
    runs.append(rows)

shapes = [s for s in runs[0] if all(s in r for r in runs)]
print(f"모든 파일에 있는 모양 {len(shapes)}개")
for path, r in zip(sys.argv[1:], runs):
    changes = [r[s][2] for s in shapes]
    in10 = sum(1 for c in changes if abs(c) <= 10)
    in20 = sum(1 for c in changes if abs(c) <= 20)
    print(f"  {path}: 10% 안 {in10}개, 20% 안 {in20}개, 가장 큰 것 {max(changes):+.1f}%, 가장 작은 것 {min(changes):+.1f}%")

print("모든 파일에서 같은 방향으로 20% 넘게 변한 모양:")
found = False
for s in shapes:
    c = [r[s][2] for r in runs]
    if all(x > 20 for x in c) or all(x < -20 for x in c):
        found = True
        print(f"  {s}\n    " + ", ".join(f"{r[s][0]:.3f} → {r[s][1]:.3f}ms ({r[s][2]:+.1f}%)" for r in runs))
if not found:
    print("  없음")
