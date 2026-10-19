"""#119의 판정. 기준은 재기 전에 #119에 적었다.

- 재현: 16배속의 늦은 비율 세 번의 중앙값이 10% 이상이면 실험 22를 재현한 것으로 본다. 아니면 아래 판정을 하지 않는다
- F = 늦은 문장 가운데 그 자리에서 새 연결을 연 문장의 몫. 16배속 세 번의 중앙값
- F >= 0.5: 새 연결을 여는 것이 주된 까닭이다 / F <= 0.2: 주된 까닭이 아니다 / 사이: 일부만 설명한다
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
		if len(f) != 11 or f[1] == "0":
			continue
		stmts, ratio, late, late_opened, opened, open_p50, open_max, lag99, secs = (
			int(f[2]), float(f[3]), int(f[4]), int(f[5]), int(f[6]), int(f[7]), int(f[8]), int(f[9]), float(f[10]))
		runs.setdefault(float(f[0]), []).append(
			dict(ratio=ratio, late=late, f=late_opened / late if late else 0.0, opened=opened, open_p50=open_p50, open_max=open_max,
				lag99=lag99, secs=secs))
	print("| 배속 | 늦은 비율(세 번) | 늦은 문장 가운데 새 연결을 연 몫 F(세 번) | 새로 연 연결(세 번) | 연결 여는 시간 중앙값/최대(ms) | 새 연결 없이 늦은 문장의 늦음 p99(ms) | 걸린 초 |")
	print("|---|---|---|---|---|---|---|")
	for speed in sorted(runs):
		rs = runs[speed]
		print(f"| {speed:g} | {', '.join(f'{r['ratio'] * 100:.1f}%' for r in rs)} | {', '.join(f'{r['f']:.2f}' for r in rs)} "
			f"| {', '.join(str(r['opened']) for r in rs)} | {', '.join(f'{r['open_p50'] / 1000:.1f}/{r['open_max'] / 1000:.1f}' for r in rs)} "
			f"| {', '.join(f'{r['lag99'] / 1000:.1f}' for r in rs)} | {', '.join(f'{r['secs']:.1f}' for r in rs)} ({30 / speed:.1f}) |")
	print()
	at16 = runs.get(16.0, [])
	if len(at16) < 3:
		print(f"- 판정하지 않는다(16배속 실행 {len(at16)}번)")
		return
	ratio16 = statistics.median(r["ratio"] for r in at16)
	print(f"- 16배속의 늦은 비율 중앙값: {ratio16 * 100:.1f}%")
	if ratio16 < 0.10:
		print("- 판정: 재현하지 못했다(10% 미만). F로 판정하지 않는다")
		return
	f16 = statistics.median(r["f"] for r in at16)
	verdict = "새 연결을 여는 것이 주된 까닭이다" if f16 >= 0.5 else "주된 까닭이 아니다" if f16 <= 0.2 else "일부만 설명한다"
	print(f"- 16배속의 F 중앙값: {f16:.2f}")
	print(f"- 판정: {verdict}")


if __name__ == "__main__":
	main()
