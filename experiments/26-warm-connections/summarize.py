"""#126의 판정. 기준은 재기 전에 #126에 적었다.

- 16배속을 미리 여는 수 16, 64, 256으로 세 번씩 돈다. 늦은 비율의 중앙값으로 본다
- 64나 256의 중앙값이 16의 중앙값의 절반 이하면 "미리 열기가 늦음을 줄인다". 절반 이하인 값 가운데 작은 것을 기본값으로 한다
- 둘 다 16의 0.8배 이상이면 "줄이지 않는다". 기본값은 16으로 둔다
- 그 밖은 "판정하지 못한다". 기본값은 16으로 둔다
- 데우기(반복 0)는 세지 않는다
"""
import os
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.environ.get("OUT", "out"))


def main():
	runs = {}
	for line in open(os.path.join(OUT, "bench.txt"), encoding="utf-8"):
		f = line.split()
		if len(f) != 7 or f[1] == "0":
			continue
		runs.setdefault(int(f[0]), []).append((float(f[2]), int(f[3]), int(f[4]), float(f[5]), float(f[6])))
	print("| 미리 여는 연결 | 늦은 비율(세 번) | 중앙값 | 도중에 연 연결 | 연결 여는 시간 중앙값(ms) | 미리 열기와 재생을 합친 초 | 재생 초 |")
	print("|---|---|---|---|---|---|---|")
	med = {}
	for warm in sorted(runs):
		rs = runs[warm]
		med[warm] = statistics.median(r[0] for r in rs)
		col = lambda i, f: ", ".join(f(r[i]) for r in rs)
		print(f"| {warm} | {col(0, lambda v: f'{v * 100:.1f}%')} | {med[warm] * 100:.1f}% | {col(1, str)} | {col(2, lambda v: f'{v / 1000:.1f}')} "
			f"| {col(3, lambda v: f'{v:.2f}')} | {col(4, lambda v: f'{v:.2f}')} |")
	print()
	if any(len(runs.get(w, [])) < 3 for w in (16, 64, 256)):
		print("- 판정하지 않는다(세 번이 안 된 값이 있다)")
		return
	base = med[16]
	halves = [w for w in (64, 256) if med[w] <= base / 2]
	if halves:
		print(f"- 판정: 미리 열기가 늦음을 줄인다. 기본값을 {min(halves)}으로 한다")
	elif med[64] >= base * 0.8 and med[256] >= base * 0.8:
		print("- 판정: 줄이지 않는다. 기본값은 16으로 둔다")
	else:
		print("- 판정: 판정하지 못한다. 기본값은 16으로 둔다")
	print(f"- 16의 중앙값 {base * 100:.1f}%, 64는 {med[64] / base:.2f}배, 256은 {med[256] / base:.2f}배")


if __name__ == "__main__":
	main()
