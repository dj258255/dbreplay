"""두 트래픽 파일(서버 로그에서, 패킷 캡처에서)의 문장을 맞춰 본다. 리터럴을 ?로 바꾼 모양별 횟수를 비교한다."""
import json, re, sys
from collections import Counter

def shapes(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    recs = [json.loads(l) for l in lines[1:]]
    stmts = [r for r in recs if r.get("sql")]
    shape = Counter()
    for r in stmts:
        s = re.sub(r"'(?:[^']|'')*'", "?", r["sql"])
        s = re.sub(r"\b\d+(\.\d+)?\b", "?", s)
        s = re.sub(r"\s+", " ", s).strip().upper()
        shape[s] += 1
    errors = sum(1 for r in stmts if r.get("error"))
    sessions = len({r["session"] for r in recs})
    durations = sorted(r.get("durationMicros", 0) for r in stmts)
    med = durations[len(durations) // 2] if durations else 0
    return len(stmts), shape, errors, sessions, med

a_n, a, a_err, a_s, a_med = shapes(sys.argv[1])
b_n, b, b_err, b_s, b_med = shapes(sys.argv[2])
print(f"문장 수: 서버 로그 {a_n}, 패킷 캡처 {b_n} (차이 {b_n - a_n:+d})")
print(f"연결 수: {a_s}, {b_s}. 실패한 문장: {a_err}, {b_err}. 걸린 시간 중앙값(마이크로초): {a_med}, {b_med}")
same = sum(1 for k in set(a) | set(b) if a[k] == b[k])
print(f"모양 {len(set(a) | set(b))}개 가운데 횟수가 같은 것 {same}개")
diff = sorted(((k, a[k], b[k]) for k in set(a) | set(b) if a[k] != b[k]), key=lambda x: -abs(x[1] - x[2]))
for k, x, y in diff[:10]:
    print(f"  {x:>7} → {y:>7}  {k[:110]}")
