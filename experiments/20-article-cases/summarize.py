"""실험 20의 판정(#89). 기준은 재기 전에 #89에 적었다.

- 무효: 그 탐색 동안 디스크 탐침(앞뒤 5개 중앙값)이 8,180µs 이상인 때가 30초 넘게 이어졌다(실험 21, 17과 같다)
- 1 사양 축소: CPU 2의 상한 최소가 요구 배수 2 이상이면 "줄여도 요구를 채운다"
- 2 서버 비교: 두 범위가 겹치지 않으면 "다르다", 겹치면 "구분되지 않는다"
- 3 비례: 2대 ÷ 1대가 최소끼리와 중앙값끼리 모두 1.6~2.4배 안이면 "맞았다", 모두 밖이면 "맞지 않았다", 갈리면 "판정하지 못한다"
"""
import os
import re
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
SLOW = 8180


def disk():
	path = os.path.join(OUT, "disk-probe.txt")
	rows = []
	if os.path.exists(path):
		for line in open(path, encoding="utf-8"):
			f = line.split()
			if len(f) == 2 and f[0].isdigit() and f[1].lstrip("-").isdigit():
				rows.append((int(f[0]), int(f[1])))
	return [(t, statistics.median(v for _, v in rows[max(0, i - 2):i + 3])) for i, (t, _) in enumerate(rows)]


def windows():
	out = {}
	path = os.path.join(OUT, "times.txt")
	if os.path.exists(path):
		for line in open(path, encoding="utf-8"):
			name, t0, t1 = line.split()
			out[name] = (int(t0), int(t1))
	return out


def ceiling(name):
	path = os.path.join(OUT, name + ".txt")
	if not os.path.exists(path):
		return None
	text = open(path, encoding="utf-8").read()
	every = re.search(r"탐색마다: (.*)", text)
	if not every:
		return None
	v = sorted(float(x) for x in every.group(1).split(","))
	distorted = len(re.findall(r"왜곡", text))
	return {"values": every.group(1), "min": v[0], "median": statistics.median(v), "max": v[-1], "distorted": distorted}


def longest_slow(d, t0, t1):
	longest = run = 0
	for t, v in d:
		if t0 <= t <= t1:
			run = run + 1 if v >= SLOW else 0
			longest = max(longest, run)
	return longest


def main():
	d = disk()
	w = windows()
	names = ["1-cpu4", "1-cpu2", "2-pool134217728", "2-pool33554432", "3-replicas1", "3-replicas2"]
	got = {}
	print("| 탐색 | 탐색마다의 상한 | 최소 | 중앙값 | 최대 | '왜곡'이 나온 줄 | 탐침이 가장 길게 이어서 넘은 초 | 쓰는가 |")
	print("|---|---|---|---|---|---|---|---|")
	for n in names:
		c = ceiling(n)
		if c is None:
			print(f"| {n} | 값 없음 | | | | | | |")
			continue
		slow = longest_slow(d, *w[n]) if n in w and d else None
		use = slow is not None and slow <= 30
		got[n] = c if use else None
		print(f"| {n} | {c['values']} | {c['min']:.2f} | {c['median']:.2f} | {c['max']:.2f} | {c['distorted']} | {slow if slow is not None else '기록 없음'} | {'쓴다' if use else '무효'} |")
	print()
	if got.get("1-cpu2"):
		c = got["1-cpu2"]
		print(f"- 1 사양 축소: CPU 2의 최소 {c['min']:.2f}배속 → {'요구 2배를 채운다. 줄여도 된다' if c['min'] >= 2 else '요구 2배를 채우지 못한다'}")
	if got.get("2-pool134217728") and got.get("2-pool33554432"):
		a, b = got["2-pool134217728"], got["2-pool33554432"]
		apart = a["max"] < b["min"] or b["max"] < a["min"]
		print(f"- 2 서버 비교: 128MB {a['min']:.2f}~{a['max']:.2f}, 32MB {b['min']:.2f}~{b['max']:.2f} → {'다르다' if apart else '구분되지 않는다'}")
	if got.get("3-replicas1") and got.get("3-replicas2"):
		a, b = got["3-replicas1"], got["3-replicas2"]
		r_min = b["min"] / a["min"] if a["min"] else float("nan")
		r_med = b["median"] / a["median"] if a["median"] else float("nan")
		inside = [1.6 <= r <= 2.4 for r in (r_min, r_med)]
		verdict = "이 조건에서 맞았다" if all(inside) else "맞지 않았다" if not any(inside) else "판정하지 못한다(최소와 중앙값이 갈림)"
		print(f"- 3 비례: 2대 ÷ 1대 최소 {r_min:.2f}배, 중앙값 {r_med:.2f}배 → {verdict}")


if __name__ == "__main__":
	main()
