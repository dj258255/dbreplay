"""run.sh의 출력에서 무너진 초와 입출력 압력이 겹치는지 낸다(#99). 기준은 재기 전에 #99에 적은 것이다.

- 무너진 초: 1초 처리량이 그 구간 중앙값의 50% 아래
- 압력이 높은 표본: io full avg10 5% 이상
- 그 초를 덮는 표본: 그 초 뒤의 첫 표본(avg10은 표본 앞 10초의 평균이다)
- 겹친다: 무너진 초의 70% 이상이 압력이 높은 표본에 덮인다. 30% 미만이면 겹치지 않는다
"""
import os
import re
import statistics

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
COLLAPSE = 0.5
HIGH = 5.0


def psi():
    rows = []
    for line in open(os.path.join(OUT, "psi.txt")):
        m = re.match(r"(\d+) io some avg10=([\d.]+) .*?total=(\d+) full avg10=([\d.]+) .*?total=(\d+) cpu some avg10=([\d.]+)", line)
        if m:
            t, io_some, some_total, io_full, full_total, cpu = m.groups()
            rows.append({"t": int(t), "io_some": float(io_some), "io_full": float(io_full), "full_total": int(full_total), "cpu": float(cpu)})
    # 누적값의 차이로 본 표본 사이의 io full 비율(%). avg10보다 표본 간격에 맞는다
    for a, b in zip(rows, rows[1:]):
        b["full_pct"] = (b["full_total"] - a["full_total"]) / ((b["t"] - a["t"]) * 1e6) * 100
    rows[0]["full_pct"] = 0.0
    return rows


def series(name):
    path = os.path.join(OUT, name + ".txt")
    points = []
    if name.startswith("mysql"):
        start = int(open(os.path.join(OUT, name + ".start")).read())
        for line in open(path):
            m = re.match(r"\[ (\d+)s \] thds: \d+ tps: ([\d.]+)", line)
            if m:
                points.append((start + int(m.group(1)), float(m.group(2))))
    else:
        for line in open(path):
            m = re.match(r"progress: ([\d.]+) s, ([\d.]+) tps", line)
            if m:
                points.append((int(float(m.group(1))), float(m.group(2))))
    return points


def events():
    rows = []
    for line in open(os.path.join(OUT, "events.txt")):
        t, what = line.split(" ", 1)
        rows.append((int(t), what.strip()))
    return rows


def cover(rows, s):
    for r in rows:
        if r["t"] >= s:
            return r
    return None


def runs(seconds):
    """이어진 초를 구간으로 묶는다."""
    out = []
    for s in seconds:
        if out and s - out[-1][1] <= 1:
            out[-1][1] = s
        else:
            out.append([s, s])
    return out


def main():
    p = psi()
    ev = events()
    print(f"압력 표본 {len(p)}개, 맥 부하 평균은 out/host-load.txt")
    total_collapsed = total_covered = 0
    for name in ["mysql-a", "mysql-b", "pg-a", "pg-b"]:
        if not os.path.exists(os.path.join(OUT, name + ".txt")):
            continue
        pts = series(name)
        if not pts:
            print(f"\n## {name}: 처리량 줄이 없다")
            continue
        med = statistics.median(v for _, v in pts)
        collapsed = [s for s, v in pts if v < med * COLLAPSE]
        covered = [s for s in collapsed if (r := cover(p, s)) and r["io_full"] >= HIGH]
        first, last = pts[0][0], pts[-1][0]
        inside = [r for r in p if first <= r["t"] <= last + 5]
        high = [r for r in inside if r["io_full"] >= HIGH]
        total_collapsed += len(collapsed)
        total_covered += len(covered)
        print(f"\n## {name}: {len(pts)}초, 중앙값 {med:.0f} tps")
        print(f"- 무너진 초 {len(collapsed)}개, 그 가운데 압력이 높은 표본에 덮인 것 {len(covered)}개"
              + (f" ({len(covered) / len(collapsed):.0%})" if collapsed else ""))
        print(f"- 구간 안의 표본 {len(inside)}개, 압력이 높은 표본 {len(high)}개, io full avg10 최대 {max((r['io_full'] for r in inside), default=0):.2f}%, "
              f"누적값으로 본 최대 {max((r.get('full_pct', 0) for r in inside), default=0):.2f}%")
        # 압력이 높은 표본 뒤집어 보기: 그 표본이 덮는 5초에 무너진 초가 있었나
        hit = sum(1 for r in high if any(r["t"] - 5 < s <= r["t"] for s in collapsed))
        if high:
            print(f"- 압력이 높은 표본 {len(high)}개 가운데 덮는 5초에 무너진 초가 있던 것 {hit}개")
        print("- 무너진 구간 (시작~끝 시각, 초, 그 구간의 최저 tps, 덮는 표본의 io full avg10 최대, 누적값 최대, cpu some avg10 최대)")
        for a, b in runs(collapsed):
            span = [r for r in p if a <= r["t"] <= b + 5]
            low = min(v for s, v in pts if a <= s <= b)
            print(f"  {a}~{b} {b - a + 1}초 최저 {low:.0f} io_full {max((r['io_full'] for r in span), default=0):.2f}% "
                  f"누적 {max((r.get('full_pct', 0) for r in span), default=0):.2f}% cpu {max((r['cpu'] for r in span), default=0):.2f}%")
        # 일으킨 일 뒤 10초
        for t, what in ev:
            if first <= t <= last and what.split()[0] in ("write-start", "write-end", "delete-start"):
                window = [(s, v) for s, v in pts if t <= s <= t + 10]
                if window:
                    low = min(v for _, v in window)
                    n = sum(1 for _, v in window if v < med * COLLAPSE)
                    span = [r for r in p if t <= r["t"] <= t + 15]
                    print(f"- {what.split()[0]} {t} 뒤 10초: 최저 {low:.0f} tps ({low / med:.0%}), 무너진 초 {n}개, "
                          f"io full avg10 최대 {max((r['io_full'] for r in span), default=0):.2f}%")
    print(f"\n## 모두: 무너진 초 {total_collapsed}개, 압력이 높은 표본에 덮인 것 {total_covered}개"
          + (f" ({total_covered / total_collapsed:.0%})" if total_collapsed else ""))


if __name__ == "__main__":
    main()
