"""#117의 판정. 기준은 재기 전에 #117에 적었다.

- 배속마다 세 번의 중앙값으로 본다. 왜곡 기준은 explore의 기본값 20%다. L은 중앙값이 20%를 넘는 가장 낮은 배속이다
- L <= 24: 재생기 혼자서도 막힌다
- L >= 48이거나 64까지 넘지 않는다: 재생기 혼자서는 막히지 않는다
- L = 32: 판정하지 못한다
- 한 배속이라도 세 번이 20%의 양쪽으로 갈리면 그렇다고 적는다
"""
import os
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.environ.get("OUT", "out"))
LIMIT = 0.2
SPAN = 30.0  # 녹화한 길이(초). 걸린 시간이 이것 ÷ 배속보다 크게 길면 장비가 잠들었거나 재생기가 밀린 것이다


def main():
	runs = {}
	for line in open(os.path.join(OUT, "bench.txt"), encoding="utf-8"):
		f = line.split()
		if len(f) == 6:
			runs.setdefault(float(f[0]), []).append((int(f[2]), float(f[3]), int(f[4]), float(f[5])))
	print("| 배속 | 문장 수 | 늦은 비율(세 번) | 중앙값 | 늦음 p99(µs, 세 번) | 걸린 초(세 번, 계획 초) |")
	print("|---|---|---|---|---|---|")
	medians, split = {}, []
	for speed in sorted(runs):
		rs = runs[speed]
		ratios = [r for _, r, _, _ in rs]
		medians[speed] = statistics.median(ratios)
		if min(ratios) <= LIMIT < max(ratios):
			split.append(speed)
		print(f"| {speed:g} | {rs[0][0]} | {', '.join(f'{r * 100:.1f}%' for r in ratios)} | {medians[speed] * 100:.1f}% "
			f"| {', '.join(str(p) for _, _, p, _ in rs)} | {', '.join(f'{e:.1f}' for _, _, _, e in rs)} ({SPAN / speed:.1f}) |")
	print()
	over = [s for s in sorted(medians) if medians[s] > LIMIT]
	low = over[0] if over else None
	if low is not None and low <= 24:
		verdict = "재생기 혼자서도 막힌다"
	elif low is None or low >= 48:
		verdict = "재생기 혼자서는 막히지 않는다"
	else:
		verdict = "판정하지 못한다"
	print(f"- 중앙값이 20%를 넘는 가장 낮은 배속: {f'{low:g}' if low is not None else '없음(64까지)'}")
	print(f"- 판정: {verdict}")
	if split:
		print(f"- 세 번이 20%의 양쪽으로 갈린 배속: {', '.join(f'{s:g}' for s in split)}")


if __name__ == "__main__":
	main()
