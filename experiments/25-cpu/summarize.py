"""#123의 판정. 기준은 재기 전에 #123에 적었다.

- U = 24배속 라운드의 장비 전체 CPU 사용률(12코어에 대한 백분율). 다섯 번의 중앙값
- U >= 85%: CPU가 찬다 / U <= 60%: CPU는 차지 않는다 / 사이: 판정하지 못한다
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
		runs.setdefault(float(f[0]), []).append(tuple(float(x) for x in f[2:]))
	print("| 배속 | 늦은 비율 | 장비 전체 CPU(12코어 중) | MySQL(코어) | 재생기(코어) | 잰 구간(초) |")
	print("|---|---|---|---|---|---|")
	for speed in sorted(runs):
		rs = runs[speed]
		col = lambda i, f: ", ".join(f(r[i]) for r in rs)
		print(f"| {speed:g} | {col(0, lambda v: f'{v * 100:.1f}%')} | {col(1, lambda v: f'{v:.0f}%')} | {col(2, lambda v: f'{v:.1f}')} "
			f"| {col(3, lambda v: f'{v:.1f}')} | {col(4, lambda v: f'{v:.2f}')} |")
	print()
	for speed in sorted(runs):
		rs = runs[speed]
		print(f"- {speed:g}배속 중앙값: 늦은 비율 {statistics.median(r[0] for r in rs) * 100:.1f}%, 장비 전체 {statistics.median(r[1] for r in rs):.0f}%, "
			f"MySQL {statistics.median(r[2] for r in rs):.1f}코어, 재생기 {statistics.median(r[3] for r in rs):.1f}코어 ({len(rs)}번)")
	at24 = runs.get(24.0, [])
	if len(at24) < 3:
		print(f"- 판정하지 않는다(24배속 실행 {len(at24)}번)")
		return
	u = statistics.median(r[1] for r in at24)
	verdict = "CPU가 찬다" if u >= 85 else "CPU는 차지 않는다" if u <= 60 else "판정하지 못한다"
	print(f"- 판정: {verdict} (24배속의 장비 전체 사용률 중앙값 {u:.0f}%)")


if __name__ == "__main__":
	main()
