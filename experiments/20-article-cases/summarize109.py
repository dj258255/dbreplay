"""#109의 판정. 기준은 재기 전에 #109에 적었다.

- 무효: 탐색 동안 디스크 탐침(앞뒤 5개 중앙값)이 8,180µs 이상인 때가 30초 넘게 이어졌다
- 판정하지 않음: 어느 쪽이든 탐색 셋 가운데 둘 이상이 재생기의 한계(왜곡)로 끝났다
- 2대 ÷ 1대가 최소끼리와 중앙값끼리 모두 1.6~2.4배 안이면 "이 조건에서 맞았다", 모두 밖이면 "맞지 않았다", 갈리면 "판정하지 못한다"
"""
import os
import re
import statistics

import summarize

O = os.path.join(summarize.HERE, "out", "109")
summarize.OUT = O


def main():
	d = summarize.disk()
	w = summarize.windows()
	got = {}
	print("| 탐색 | 탐색마다의 상한 | 최소 | 중앙값 | 최대 | 재생기의 한계로 끝난 탐색 | 알람으로 멈춘 라운드 | 탐침이 이어서 넘은 초 | 쓰는가 |")
	print("|---|---|---|---|---|---|---|---|---|")
	for n in ["replicas1", "replicas2"]:
		text = open(os.path.join(O, n + ".txt"), encoding="utf-8").read()
		c = summarize.ceiling(n)
		limited = len(re.findall(r"재생기가 내지 못해", text))
		alarms = len(re.findall(r"과부하: ", text))
		slow = summarize.longest_slow(d, *w[n]) if n in w and d else None
		ok = c is not None and slow is not None and slow <= 30 and limited < 2
		got[n] = c if ok else None
		values = c["values"] if c else "값 없음"
		nums = f"{c['min']:.2f} | {c['median']:.2f} | {c['max']:.2f}" if c else " | | "
		print(f"| {n} | {values} | {nums} | {limited} | {alarms} | {slow} | {'쓴다' if ok else '쓰지 않는다'} |")
	print()
	a, b = got.get("replicas1"), got.get("replicas2")
	if not a or not b:
		print("- 판정하지 않는다(무효이거나 재생기의 한계로 끝난 탐색이 둘 이상)")
		return
	r_min, r_med = b["min"] / a["min"], b["median"] / a["median"]
	inside = [1.6 <= r <= 2.4 for r in (r_min, r_med)]
	verdict = "이 조건에서 맞았다" if all(inside) else "맞지 않았다" if not any(inside) else "판정하지 못한다(최소와 중앙값이 갈림)"
	print(f"- 2대 ÷ 1대: 최소 {r_min:.2f}배, 중앙값 {r_med:.2f}배 → {verdict}")


if __name__ == "__main__":
	main()
