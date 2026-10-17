"""2부 네 번째 차례(#106)의 판정. 기준은 재기 전에 #106에 적었다.

- 무효(기준 가): 그 30초 동안 디스크 탐침(앞뒤 5개 중앙값)이 8,180µs 이상인 때가 있다(실험 21)
- 캡처는 30초에 1GB 넘게 같은 디스크에 쓴다. 탐침이 캡처 자신의 쓰기로 느려질 수 있다. 켬의 80% 이상이 무효이고 끔은 20% 이하이면
  기준 가는 캡처와 섞인 것으로 보고 기준 나를 쓴다: 실행 앞의 20초(쉬는 동안)와 뒤의 20초에 탐침이 문턱 이상인 때가 있으면 무효
- 끔과 켬이 모두 유효한 쌍만 쓴다. 쌍마다 (켬 - 끔) / 끔의 중앙값으로 본다. 쓸 쌍이 셋 아래면 판정하지 않는다
- 판정: 서버 로그(MySQL 12%, PostgreSQL 16%)보다 작고 5% 안이면 "방식이 다름", 서버 로그보다 작지만 5%를 넘으면 "부분", 서버 로그 이상이면 목표를 못 채움
"""
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
PREFIX = sys.argv[1] if len(sys.argv) > 1 else "4"
SLOW = 8180
LOG_COST = {"mysql": 12.0, "pg": 16.0}


def disk():
    rows = []
    # 여러 번 이어 돌리면 탐침 기록이 이어 붙는다
    for line in open(os.path.join(OUT, f"part2-{PREFIX}-disk.txt")):
        f = line.split()
        if len(f) == 2 and f[0].isdigit() and f[1].lstrip("-").isdigit():
            rows.append((int(f[0]), int(f[1])))
    rows.sort()
    return [(t, statistics.median(v for _, v in rows[max(0, i - 2):i + 3])) for i, (t, _) in enumerate(rows)]


def tps(db, mode, i):
    text = open(os.path.join(OUT, f"{db}{PREFIX}-{mode}-{i}.txt")).read()
    if db == "mysql":
        m = re.search(r"transactions:\s+\d+\s+\(([\d.]+) per sec", text)
    else:
        m = re.search(r"^tps = ([\d.]+)", text, re.M)
    return float(m.group(1)) if m else None


def main():
    d = disk()
    runs = []
    for line in open(os.path.join(OUT, f"part2-{PREFIX}-times.txt")):
        db, mode, i, t0, t1 = line.split()
        runs.append({"db": db, "mode": mode, "i": i, "t0": int(t0), "t1": int(t1)})
    for r in runs:
        during = [v for t, v in d if r["t0"] <= t <= r["t1"]]
        around = [v for t, v in d if r["t0"] - 20 <= t < r["t0"] or r["t1"] < t <= r["t1"] + 20]
        r["max_during"] = max(during, default=0)
        r["max_around"] = max(around, default=0)
        r["bad_a"] = r["max_during"] >= SLOW
        r["bad_b"] = r["max_around"] >= SLOW
        r["tps"] = tps(r["db"], r["mode"], r["i"])
    for db in ["mysql", "pg"]:
        mine = [r for r in runs if r["db"] == db]
        if not mine:
            continue
        on = [r for r in mine if r["mode"] == "on"]
        off = [r for r in mine if r["mode"] == "off"]
        on_bad = sum(r["bad_a"] for r in on) / len(on)
        off_bad = sum(r["bad_a"] for r in off) / len(off)
        rule = "가"
        if on_bad >= 0.8 and off_bad <= 0.2:
            rule = "나"
        key = "bad_a" if rule == "가" else "bad_b"
        print(f"\n## {db}: 기준 가로 무효인 비율 켬 {on_bad:.0%}, 끔 {off_bad:.0%} → 기준 {rule}")
        print("| 쌍 | 끔 tps | 켬 tps | 차이 | 끔 탐침 최대 (도중·앞뒤) | 켬 탐침 최대 (도중·앞뒤) | 쓰는가 |")
        print("|---|---|---|---|---|---|---|")
        diffs = []
        for i in sorted({r["i"] for r in mine}, key=int):
            a = next((r for r in off if r["i"] == i), None)
            b = next((r for r in on if r["i"] == i), None)
            if not a or not b or a["tps"] is None or b["tps"] is None:
                continue
            diff = (b["tps"] - a["tps"]) / a["tps"] * 100
            use = not a[key] and not b[key]
            if use:
                diffs.append(diff)
            print(f"| {i} | {a['tps']:,.0f} | {b['tps']:,.0f} | {diff:+.1f}% | {a['max_during']:.0f}·{a['max_around']:.0f} | "
                  f"{b['max_during']:.0f}·{b['max_around']:.0f} | {'쓴다' if use else '무효'} |")
        if len(diffs) < 3:
            print(f"- 쓸 수 있는 쌍 {len(diffs)}개. 셋 아래라 판정하지 않는다")
            continue
        med = statistics.median(diffs)
        cost = -med
        if cost < LOG_COST[db] and cost <= 5:
            verdict = "방식이 다름 (서버 로그보다 작고 5% 안)"
        elif cost < LOG_COST[db]:
            verdict = "부분 (서버 로그보다 작지만 5%를 넘음)"
        else:
            verdict = "목표를 채우지 못함 (서버 로그 이상)"
        print(f"- 쓸 수 있는 쌍 {len(diffs)}개, 차이의 중앙값 {med:+.1f}% → {verdict}")


if __name__ == "__main__":
    main()
