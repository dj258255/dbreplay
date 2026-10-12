"""out/explore-<조합>-<회차>.txt에서 상한을 모아 조합별로 최소, 중앙값, 최대를 낸다."""
import glob
import re
import statistics

conditions = ["30s-nowait", "30s-wait", "5m-nowait", "5m-wait"]
print("| 조합 | 실행마다의 상한 배속 | 최소 | 중앙값 | 최대 | (최대-최소)/중앙값 |")
print("|---|---|---|---|---|---|")
for c in conditions:
    values, notes = [], []
    for path in sorted(glob.glob(f"out/explore-{c}-*.txt")):
        text = open(path, encoding="utf-8").read()
        m = re.search(r"^상한: ([\d.]+)배속(.*)$", text, re.M)
        if m is None:
            notes.append("값 없음")
            continue
        value = float(m.group(1))
        # '버티지 못했다'는 시작 배속에서도 알람이 울린 것이다. 시작 배속 미만으로만 안다
        if "버티지 못했다" in m.group(2):
            notes.append(f"{value:g} 미만")
            continue
        values.append(value)
        notes.append(f"{value:g}" + ("+" if "이상" in m.group(2) else ""))
    if values:
        med = statistics.median(values)
        print(f"| {c} | {', '.join(notes)} | {min(values):g} | {med:g} | {max(values):g} | {(max(values) - min(values)) / med * 100:.0f}% |")
    else:
        print(f"| {c} | {', '.join(notes)} | | | | |")
