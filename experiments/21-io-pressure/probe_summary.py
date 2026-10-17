"""probe.sh의 출력에서 탐침을 무효 기준으로 쓸 수 있는지 낸다(#99). 기준은 재기 전에 #99에 적은 것이다.

- 라: 무너진 초(1초 처리량이 중앙값의 50% 아래)의 탐침 지연 중앙값이 정상 초 중앙값의 3배 이상이고,
  정상 초 가운데 그 값을 넘는 것이 5% 이하면 탐침을 무효 기준으로 쓴다
- 다: 탐침 지연이 다 구간 중앙값의 3배 이상인 상태가 30초 넘게 이어지면 DB 부하 없이도 디스크가 느려진다
  탐침 하나하나는 흔들리므로 앞뒤 5개의 중앙값으로 본다
"""
import os
import statistics

import summarize

OUT = summarize.OUT


def probe():
    rows = {}
    for line in open(os.path.join(OUT, "probe.txt")):
        f = line.split()
        if len(f) == 2 and f[0].isdigit():
            rows[int(f[0])] = int(f[1])
    return rows


def smooth(rows):
    keys = sorted(rows)
    out = {}
    for i, k in enumerate(keys):
        window = [rows[x] for x in keys[max(0, i - 2):i + 3]]
        out[k] = statistics.median(window)
    return out


def events():
    return {what: t for t, what in ((int(a), b.strip()) for a, b in (line.split(" ", 1) for line in open(os.path.join(OUT, "probe-events.txt"))))}


def main():
    raw = probe()
    sm = smooth(raw)
    ev = events()
    idle = {t: v for t, v in sm.items() if ev["start idle"] <= t <= ev["end idle"]}
    med = statistics.median(raw[t] for t in idle)
    print(f"## 다: 부하 없이 {len(idle)}개, 탐침 지연 중앙값 {med:.0f}us, 최대 {max(raw[t] for t in idle)}us")
    slow = sorted(t for t, v in idle.items() if v >= 3 * med)
    long_runs = [(a, b) for a, b in summarize.runs(slow) if b - a + 1 > 30]
    print(f"- 앞뒤 5개 중앙값이 {3 * med:.0f}us 이상인 표본 {len(slow)}개, 30초 넘게 이어진 구간 {len(long_runs)}개 {long_runs}")
    if long_runs:
        print("- 판정: DB 부하 없이도 디스크가 느려진다")

    pts = summarize.series("mysql-c")
    tmed = statistics.median(v for _, v in pts)
    collapsed = {s for s, v in pts if v < tmed * 0.5}
    loaded = {t: v for t, v in sm.items() if pts[0][0] <= t <= pts[-1][0]}
    bad = [v for t, v in loaded.items() if t in collapsed]
    good = [v for t, v in loaded.items() if t not in collapsed]
    print(f"\n## 라: MySQL {len(pts)}초, 중앙값 {tmed:.0f} tps, 무너진 초 {len(collapsed)}개")
    print("- 무너진 구간: " + ", ".join(f"{a}~{b}({b - a + 1}초)" for a, b in summarize.runs(sorted(collapsed))))
    if not bad:
        print("- 무너진 초가 없어 탐침을 판정하지 못한다")
        return
    bm, gm = statistics.median(bad), statistics.median(good)
    over = sum(1 for v in good if v > bm) / len(good)
    print(f"- 탐침 지연(앞뒤 5개 중앙값) 무너진 초 {bm:.0f}us, 정상 초 {gm:.0f}us ({bm / gm:.1f}배), 정상 초 중 {bm:.0f}us를 넘는 것 {over:.1%}")
    usable = bm >= 3 * gm and over <= 0.05
    print(f"- 판정: 탐침을 무효 기준으로 {'쓴다(문턱 ' + format(bm, '.0f') + 'us)' if usable else '쓰지 않는다'}")
    # 문턱을 넘은 초 가운데 무너진 초의 비율(거꾸로 본 값)
    if usable:
        hit = [t for t, v in loaded.items() if v >= bm]
        print(f"- 문턱을 넘은 초 {len(hit)}개 가운데 무너진 초 {sum(1 for t in hit if t in collapsed)}개")


if __name__ == "__main__":
    main()
