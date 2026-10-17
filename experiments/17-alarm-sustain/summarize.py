"""out/explore-s*-*.txt에서 실행마다의 상한 범위를 내고, 그 실행 동안의 도커 밖 CPU와 부하 평균을 붙인다.

판정에서 빼는 기준(#75의 07:04, 07:06 코멘트): 도커 밖 CPU(cpu.txt)와 이 실험의 것이 아닌 컨테이너의 CPU(docker-other.txt)를
같은 시각끼리 더한 값이 600(코어 6개)을 넘은 표본이 10%를 넘는 실행. 다른 컨테이너 값이 없는 시각은 도커 밖 CPU만 본다.
"""
import glob, re, os, sys

here = os.path.dirname(os.path.abspath(__file__))


def samples(name):
	path = os.path.join(here, "out", name)
	if not os.path.exists(path):
		return []
	out = []
	for line in open(path, encoding="utf-8"):
		parts = line.split()
		if len(parts) == 2:
			out.append((parts[0], float(parts[1])))
	return out


def seconds(t):
	h, m, x = t.split(":")
	return int(h) * 3600 + int(m) * 60 + int(x)


other = samples("docker-other.txt")


def outside(t, v):
	"""그 시각의 바깥 부하. 30초 안의 가장 가까운 다른 컨테이너 값을 더한다."""
	near = [(abs(seconds(t) - seconds(u)), w) for u, w in other if abs(seconds(t) - seconds(u)) <= 30]
	return v + (min(near)[1] if near else 0)


cpu = [(t, outside(t, v)) for t, v in samples("cpu.txt")]
load = samples("load.txt")

# 디스크 탐침(실험 21). 앞뒤 5개의 중앙값이 8,180µs 이상인 때가 30초 넘게 이어진 실행은 무효다(#75의 10-17 코멘트).
# 되돌리기가 스냅샷을 되부어 몇 초 동안 디스크를 세게 쓰므로 "한 번이라도"는 기준으로 쓰지 않고 함께 적기만 한다
import time, statistics
SLOW = 8180
disk = []
if os.path.exists(os.path.join(here, "out", "disk-probe.txt")):
	for line in open(os.path.join(here, "out", "disk-probe.txt"), encoding="utf-8"):
		parts = line.split()
		if len(parts) == 2 and parts[0].isdigit() and parts[1].lstrip("-").isdigit():
			disk.append((time.strftime("%H:%M:%S", time.localtime(int(parts[0]))), int(parts[1])))
disk = [(t, statistics.median(v for _, v in disk[max(0, i - 2):i + 3])) for i, (t, _) in enumerate(disk)]


def slow_disk(start, end):
	"""그 실행 동안 탐침이 문턱 이상인 표본 수와 가장 길게 이어진 초."""
	inside = [v for t, v in disk if start <= t <= end]
	longest = run = 0
	for v in inside:
		run = run + 1 if v >= SLOW else 0
		longest = max(longest, run)
	return len(inside), sum(1 for v in inside if v >= SLOW), longest

print("| 연속 횟수 | 차례 | 탐색마다의 상한 | 최소 | 중앙값 | 최대 | 퍼짐 | 바깥 CPU 중앙값·최대 (600 넘은 비율) | 부하 평균 최대 | CPU 기준 | 2배속에서 무너진 탐색 | 탐침 표본·문턱 이상·가장 긴 이어짐(초) | 탐침 기준 |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for f in sorted(glob.glob(os.path.join(here, "out", sys.argv[1] if len(sys.argv) > 1 else "", "explore-s*-*.txt")), key=lambda p: int(re.search(r"-(\d+)\.txt$", p).group(1))):
	sustain, n = re.search(r"explore-s(\d+)-(\d+)\.txt$", f).groups()
	text = open(f, encoding="utf-8").read()
	every = re.search(r"탐색마다: (.*)", text)
	r = None
	if every:
		# 퍼짐은 상한 값으로 직접 계산한다. 중앙값이 0이고 갈렸으면 낼 수 없다(#91)
		v = sorted(float(x) for x in every.group(1).split(","))
		med = v[len(v) // 2] if len(v) % 2 else (v[len(v) // 2 - 1] + v[len(v) // 2]) / 2
		spread = "0%" if v[-1] == v[0] else ("낼 수 없음" if med == 0 else f"{(v[-1] - v[0]) / med:.0%}")
		r = (v[0], med, v[-1], spread)
	# 2배속 라운드의 평균 실행 중인 연결이 50 이상인 탐색: 환경이 무너진 것으로 본다(#75의 12:30 기준)
	collapsed = sum(1 for m in re.finditer(r"^1 +2\.00 +([\d.]+) / ", text, re.M) if float(m.group(1)) >= 50)
	window = re.search(r"^# (\S+) (\S+)$", text, re.M)
	cpu_text, load_text, use = "", "", "끝나지 않음"
	if window:
		inside = sorted(v for t, v in cpu if window.group(1) <= t <= window.group(2))
		over = sum(1 for v in inside if v > 600)
		if inside:
			ratio = over / len(inside)
			cpu_text = f"{inside[len(inside) // 2]:.0f}·{inside[-1]:.0f} ({ratio:.0%})"
			use = "쓴다" if ratio <= 0.10 else "빼고 다시 돈다"
		peaks = [v for t, v in load if window.group(1) <= t <= window.group(2)]
		load_text = f"{max(peaks):.1f}" if peaks else ""
	disk_text, disk_use = "", ""
	if window and disk:
		n_in, n_slow, longest = slow_disk(window.group(1), window.group(2))
		disk_text = f"{n_in}·{n_slow}·{longest}"
		disk_use = "쓴다" if longest <= 30 else "무효"
	if r:
		print(f"| {sustain} | {n} | {every.group(1)} | {r[0]:.2f} | {r[1]:.2f} | {r[2]:.2f} | {r[3]} | {cpu_text} | {load_text} | {use} | {collapsed} | {disk_text} | {disk_use} |")
	else:
		print(f"| {sustain} | {n} | 값 없음 | | | | | {cpu_text} | {load_text} | {use} | | {disk_text} | {disk_use} |")
