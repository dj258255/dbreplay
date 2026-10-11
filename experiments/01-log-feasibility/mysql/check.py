"""슬로우 로그만으로 복원한 트랜잭션 묶음을 performance_schema의 정답지와 대조한다.

사용: python3 mysql/check.py out/mysql-slow.log out/mysql-ps-statements.jsonl out/mysql-ps-transactions.jsonl
"""

import json
import re
import sys
from collections import defaultdict

import slowlog

_COMMENT = re.compile(r"/\*.*?\*/")
_AUTOCOMMIT = re.compile(r"autocommit\s*=\s*(0|1|on|off)", re.I)
# 실행되면 열려 있던 트랜잭션을 암묵적으로 커밋하는 문장의 첫 단어
_IMPLICIT_COMMIT = {"CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "LOCK", "UNLOCK", "GRANT", "REVOKE", "ANALYZE", "OPTIMIZE"}
# autocommit=0에서도 트랜잭션을 열지 않는 문장의 첫 단어
_NO_TXN = {"SET", "DO", "SHOW", "USE", "PREPARE", "DEALLOCATE"}


def first_words(sql):
    return _COMMENT.sub(" ", sql).upper().split()


def rebuild_from_log(entries):
    """연결별로 문장을 시작 시각 순으로 훑어 꼬리표 -> 묶음(꼬리표 집합)을 만든다."""
    by_conn = defaultdict(list)
    for e in entries:
        by_conn[e.conn].append(e)

    group_of = {}

    def emit(group):
        tags = frozenset(e.tag for e in group if e.tag)
        for t in tags:
            group_of[t] = tags

    for conn_entries in by_conn.values():
        conn_entries.sort(key=lambda e: e.start)
        autocommit, open_group = True, None
        for e in conn_entries:
            words = first_words(e.sql)
            head = words[0] if words else ""
            if e.sql.startswith("# administrator command:"):
                if "Quit" in e.sql and open_group is not None:
                    emit(open_group)  # 커밋 없이 끊긴 연결: 서버가 롤백한다
                    open_group = None
                continue
            if head == "BEGIN" or words[:2] == ["START", "TRANSACTION"]:
                if open_group is not None:
                    emit(open_group)
                open_group = [e]
            elif head in ("COMMIT", "ROLLBACK") and "SAVEPOINT" not in words:
                if open_group is not None:
                    emit(open_group + [e])
                    open_group = None
                else:
                    emit([e])
            elif head in _IMPLICIT_COMMIT:
                emit((open_group or []) + [e])
                open_group = None
            elif head == "SET" and _AUTOCOMMIT.search(e.sql):
                on = _AUTOCOMMIT.search(e.sql).group(1).lower() in ("1", "on")
                if on and open_group is not None:
                    emit(open_group)
                    open_group = None
                autocommit = on
                emit([e])
            elif open_group is not None:
                open_group.append(e)
            elif not autocommit and head not in _NO_TXN and e.errno == 0:
                open_group = [e]
            else:
                emit([e])
        if open_group is not None:
            emit(open_group)
    return group_of


def truth_from_ps(stmt_path, txn_path):
    """서버가 기록한 소속 관계로 꼬리표 -> 묶음을 만든다."""
    stmts = [json.loads(l) for l in open(stmt_path)]
    txns = [json.loads(l) for l in open(txn_path)]
    started_by = {(t["thread"], t["nest"]): t["event"] for t in txns if t["nest_type"] == "STATEMENT"}

    members, singles = defaultdict(set), []
    for s in stmts:
        m = slowlog.TAG.search(s["sql"] or "")
        if not m or m.group(1).startswith("proc."):
            continue  # 프로시저 안쪽 문장은 슬로우 로그에 따로 남지 않는다
        tag = m.group(1)
        if s["nest_type"] == "TRANSACTION":
            members[(s["thread"], s["nest"])].add(tag)
        elif (s["thread"], s["event"]) in started_by and s["name"] != "statement/sql/call_procedure":
            members[(s["thread"], started_by[(s["thread"], s["event"])])].add(tag)
        else:
            singles.append(tag)

    group_of = {t: frozenset([t]) for t in singles}
    for tags in members.values():
        for t in tags:
            group_of[t] = frozenset(tags)
    return group_of


def main(log_path, stmt_path, txn_path):
    entries = slowlog.parse(log_path)
    got = rebuild_from_log(entries)
    want = truth_from_ps(stmt_path, txn_path)
    # EXECUTE는 로그에 값이 채워진 본문으로 남는다. 꼬리표가 본문 쪽(s6.2p)으로 바뀐다.
    alias = {"s6.4": "s6.2p"}
    want = {alias.get(t, t): frozenset(alias.get(x, x) for x in g) for t, g in want.items()}
    want.pop("s6.2", None)  # PREPARE는 로그에 "PREPARE st FROM ..."로만 남아 꼬리표가 없다

    key = lambda t: [int(x) for x in re.findall(r"\d+", t)]
    bad = 0
    for tag in sorted(set(got) | set(want), key=key):
        g, w = got.get(tag), want.get(tag)
        ok = g == w
        bad += not ok
        show = lambda s: "-" if s is None else "{" + ",".join(sorted(s, key=key)) + "}"
        print(f"{'OK ' if ok else 'BAD'} {tag:7} 로그 복원={show(g):38} 정답={show(w)}")
    print(f"\n문장 {len(set(got) | set(want))}개 중 불일치 {bad}개")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
