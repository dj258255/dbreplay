"""#113의 판정. 기준은 재기 전에 #113에 적었다.

- 무효: 탐색 동안 디스크 탐침(앞뒤 5개 중앙값)이 8,180µs 이상인 때가 30초 넘게 이어졌다(#99의 explore 규칙)
- 조건마다 쓸 수 있는 실행이 셋 미만이면 판정하지 않는다
- 오른다: (가) workers 4의 과반이 알람으로 끝나고 그 중앙값이 workers 1의 중앙값 이상, 또는 (나) workers 4의 최솟값 > workers 1의 최댓값
- 오르지 않는다: workers 4의 과반이 재생기의 한계로 끝나고 그 최댓값이 workers 1의 최댓값 이하
- 그 밖은 판정하지 못한다
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


def longest_slow(d, t0, t1):
	longest = run = 0
	for t, v in d:
		if t0 <= t <= t1:
			run = run + 1 if v >= SLOW else 0
			longest = max(longest, run)
	return longest


def result(name):
	"""탐색 하나의 상한 값과 끝난 까닭. 알람, 재생기의 한계, 최고 배속 가운데 하나다."""
	path = os.path.join(OUT, name + ".txt")
	if not os.path.exists(path):
		return None
	m = re.search(r"^상한: ([0-9.]+)배속(.*)$", open(path, encoding="utf-8").read(), re.M)
	if not m:
		return None
	rest = m.group(2)
	why = "재생기의 한계" if "재생기가 내지 못해" in rest else "최고 배속" if "알람이 울리지 않았다" in rest else "알람"
	return float(m.group(1)), why


def max_late(name):
	"""라운드 가운데 가장 큰 '늦음 p99'와 왜곡 비율. 재생기가 얼마나 밀렸는지 본다."""
	text = open(os.path.join(OUT, name + ".txt"), encoding="utf-8").read()
	rows = re.findall(r"^\d+\s+([0-9.]+)\s+[0-9.]+\s+[0-9.]+\s+([0-9.]+)ms\s+([0-9.]+)%", text, re.M)
	return max(((float(s), float(p), float(r)) for s, p, r in rows), key=lambda x: x[2], default=None)


def main():
	d = disk()
	w = windows()
	names = sorted((n for n in w), key=lambda n: (int(n.split("-")[1]), n))
	got = {"1": [], "4": []}
	print("| 탐색 | 상한 | 끝난 까닭 | 왜곡이 가장 큰 라운드(배속, 늦음 p99, 왜곡) | 탐침이 이어서 넘은 초 | 쓰는가 |")
	print("|---|---|---|---|---|---|")
	for n in names:
		r = result(n)
		slow = longest_slow(d, *w[n]) if d else None
		use = r is not None and slow is not None and slow <= 30
		late = max_late(n)
		late_s = f"{late[0]:.2f}, {late[1]:.3f}ms, {late[2]:.1f}%" if late else ""
		if use:
			got[n[1:].split("-")[0]].append(r)
		print(f"| {n} | {f'{r[0]:.2f}' if r else '값 없음'} | {r[1] if r else ''} | {late_s} | {slow if slow is not None else '기록 없음'} | {'쓴다' if use else '무효'} |")
	print()
	one, four = got["1"], got["4"]
	if len(one) < 3 or len(four) < 3:
		print(f"- 판정하지 않는다(쓸 수 있는 실행 workers 1: {len(one)}, workers 4: {len(four)})")
		return
	v1, v4 = [v for v, _ in one], [v for v, _ in four]
	alarm4 = sum(1 for _, why in four if why == "알람")
	limit4 = sum(1 for _, why in four if why == "재생기의 한계")
	for label, rs in (("workers 1", one), ("workers 4", four)):
		vs = [v for v, _ in rs]
		whys = ", ".join(f"{why} {sum(1 for _, x in rs if x == why)}" for why in ("알람", "재생기의 한계", "최고 배속"))
		print(f"- {label}: 최소 {min(vs):.2f}, 중앙값 {statistics.median(vs):.2f}, 최대 {max(vs):.2f}배속 ({len(rs)}번, {whys})")
	if (alarm4 * 2 > len(four) and statistics.median(v4) >= statistics.median(v1)) or min(v4) > max(v1):
		verdict = "오른다"
	elif limit4 * 2 > len(four) and max(v4) <= max(v1):
		verdict = "오르지 않는다"
	else:
		verdict = "판정하지 못한다"
	print(f"- 판정: {verdict}")


if __name__ == "__main__":
	main()
