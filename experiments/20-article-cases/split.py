"""읽기 문장만 남기고 연결 단위로 두 반쪽으로 나눈다(#109).

종 DB 쪽 재생(--replica-trace)은 BEGIN과 COMMIT까지 문장 하나씩 독립으로 보낸다. 주 쪽의 --only reads와 같은 것을 보내려고
SELECT만 남긴다. 연결은 문장 수가 많은 것부터 가벼운 쪽에 넣어 두 반쪽의 문장 수를 맞춘다(무거운 연결 8개가 4개씩 갈린다).

  python3 split.py <트래픽 파일> <읽기 전체> <반쪽 A> <반쪽 B>
"""
import collections
import json
import sys

src, full, a, b = sys.argv[1:5]
lines = open(src, encoding="utf-8").read().splitlines()
header, records = lines[0], [json.loads(line) for line in lines[1:]]
reads = [r for r in records if r.get("close") or r.get("sql", "").lstrip().upper().startswith("SELECT")]
count = collections.Counter(r["session"] for r in reads if not r.get("close"))
halves = [set(), set()]
load = [0, 0]
for session, n in count.most_common():
	side = 0 if load[0] <= load[1] else 1
	halves[side].add(session)
	load[side] += n
for path, keep in [(full, None), (a, halves[0]), (b, halves[1])]:
	with open(path, "w", encoding="utf-8") as out:
		out.write(header + "\n")
		for r in reads:
			if keep is None or r["session"] in keep:
				out.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
heavy = [s for s, n in count.most_common() if n > max(count.values()) / 10]
print(f"읽기 문장 {sum(count.values())}개, 반쪽 A {load[0]}개(연결 {len(halves[0])}개), B {load[1]}개(연결 {len(halves[1])}개), "
	f"무거운 연결 A {sum(s in halves[0] for s in heavy)}개, B {sum(s in halves[1] for s in heavy)}개")
