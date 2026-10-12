"""out/의 재생 출력과 탐색 출력을 표로 모은다. 사용: python3 summarize.py mysql|pg"""
import glob
import re
import sys

tag = sys.argv[1]


def blocks(path):
    """재생 출력을 주 DB의 것과 종 DB에 함께 튼 것으로 나눈다."""
    text = open(path, encoding="utf-8").read()
    head, _, rest = text.partition("종 DB에 함께 튼 트래픽")
    return head, rest


def exec_time(block):
    med = re.search(r"실행 시간 중앙값: 원본 [\d.]+ms, 재생 ([\d.]+)ms", block)
    p99 = re.search(r"실행 시간 p99:\s+원본 [\d.]+ms, 재생 ([\d.]+)ms", block)
    return (med.group(1), p99.group(1)) if med and p99 else ("-", "-")


def metric(text, name):
    m = re.search(rf"^{name}\s+\d+\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)", text, re.M)
    return (m.group(2), m.group(3)) if m else ("-", "-")


print(f"### {tag}: 1배속\n")
print("| 무엇을 틀었나 | 회차 | 주 DB 실행 시간 중앙값 / p99 (ms) | 종 DB 실행 시간 중앙값 / p99 (ms) | 복제 지연 평균 / 최대 (초) |")
print("|---|---|---|---|---|")
for mode, label in (("p", "주만"), ("r", "종만"), ("b", "함께")):
    for i in (1, 2, 3):
        path = f"out/{tag}-{mode}-{i}.txt"
        head, rest = blocks(path)
        primary = exec_time(head) if mode != "r" else ("-", "-")
        replica = exec_time(head) if mode == "r" else exec_time(rest) if mode == "b" else ("-", "-")
        lag = metric(open(path, encoding="utf-8").read(), "replication_lag_seconds")
        print(f"| {label} | {i} | {primary[0]} / {primary[1]} | {replica[0]} / {replica[1]} | {lag[0]} / {lag[1]} |")

print(f"\n### {tag}: 탐색\n")
print("| 무엇을 틀었나 | 회차 | 상한 | 과부하로 판정된 라운드의 이유 |")
print("|---|---|---|---|")
by_speed = {}
for kind, label in (("both", "함께"), ("primary", "주만")):
    for path in sorted(glob.glob(f"out/{tag}-explore-{kind}-*.txt")):
        text = open(path, encoding="utf-8").read()
        ceiling = re.search(r"^상한: (.*)$", text, re.M)
        reasons = re.findall(r"^\d+\s+([\d.]+)\s.*과부하: (.*)$", text, re.M)
        print(f"| {label} | {path[-5]} | {ceiling.group(1) if ceiling else '값 없음'} | "
              + "; ".join(f"{s}배속 {r}" for s, r in reasons) + " |")
        # 라운드별 지표 표에서 배속과 복제 지연(평균 / 최대)을 읽는다
        table = text.partition("라운드별 지표 (평균 / 최대)")[2]
        header = table.strip().split("\n")[0].split() if table.strip() else []
        if "replication_lag_seconds" in header:
            col = header.index("replication_lag_seconds") - 2
            for line in table.strip().split("\n")[1:]:
                m = re.match(r"^\d+\s+([\d.]+)\s+(.*)$", line)
                if not m:
                    break
                cells = re.findall(r"([\d.]+) / ([\d.]+)", m.group(2))
                if col < len(cells):
                    by_speed.setdefault((label, float(m.group(1))), []).append(cells[col][1])

print(f"\n### {tag}: 배속별 복제 지연의 최대 (초, 탐색의 라운드에서)\n")
print("| 배속 | 함께 | 주만 |")
print("|---|---|---|")
for speed in sorted({s for _, s in by_speed}):
    print(f"| {speed:g} | {', '.join(by_speed.get(('함께', speed), []))} | {', '.join(by_speed.get(('주만', speed), []))} |")
