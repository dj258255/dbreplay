"""MySQL 슬로우 로그(log_slow_extra=ON)를 문장 단위 레코드로 읽는다. 표준 라이브러리만 쓴다."""

import re
from dataclasses import dataclass

_ID = re.compile(r"Id:\s+(\d+)")
_NUM = re.compile(r"(Query_time|Lock_time):\s+([\d.]+)")
_INT = re.compile(r"(Errno|Killed|Rows_sent|Rows_examined):\s+(\d+)")
_TS = re.compile(r"(Start|End):\s+(\S+)")
TAG = re.compile(r"/\* (s\d+\.\d+p?|proc\.\d+) \*/")


@dataclass
class Entry:
    conn: int = 0
    query_time: float = 0.0
    lock_time: float = 0.0
    errno: int = 0
    killed: int = 0
    start: str = ""
    end: str = ""
    sql: str = ""
    order: int = 0  # 파일에 적힌 순서. 문장이 끝난 순서와 같다.

    @property
    def tag(self):
        m = TAG.search(self.sql)
        return m.group(1) if m else None


def parse(path):
    entries, cur, body = [], None, []

    def close():
        if cur is not None:
            cur.sql = " ".join(" ".join(body).split())
            cur.order = len(entries)
            entries.append(cur)

    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("# Time:"):
                close()
                cur, body = Entry(), []
            elif cur is None:
                continue  # 서버가 파일을 열 때 적는 머리말
            elif line.startswith("# User@Host:"):
                cur.conn = int(_ID.search(line).group(1))
            elif line.startswith("# Query_time:"):
                for k, v in _NUM.findall(line):
                    setattr(cur, k.lower(), float(v))
                for k, v in _INT.findall(line):
                    if k in ("Errno", "Killed"):
                        setattr(cur, k.lower(), int(v))
                for k, v in _TS.findall(line):
                    setattr(cur, k.lower(), v)
            elif line.startswith("SET timestamp=") or line.startswith("use "):
                continue
            elif line.startswith("/usr/sbin/mysqld") or line.startswith("Tcp port:") or line.startswith("Time  "):
                continue
            else:
                body.append(line)
    close()
    return entries
